// Actions scratch setup only. No test, application, seed or database construction.
import { env } from 'cloudflare:test';
import { afterAll } from 'vitest';
import { sha256 } from '@noble/hashes/sha256';

const projection = '__E2_PROJECTION__';
const savedDB = env.DB;
const savedLog = console.log;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
let rawEmit;
let emitCount = 0;
let unexpectedLogCalls = 0;
let attemptedReads = [];
function capture(...args) {
  if (args.length === 1 && typeof args[0] === 'string' && args[0].startsWith('CAPACITY_RESULT ')) {
    emitCount++;
    if (emitCount === 1) rawEmit = args[0];
  } else {
    unexpectedLogCalls++;
  }
  return savedLog.apply(this, args);
}
console.log = capture;

const user = '00000000-0000-4000-8000-000000000001';
const fixed = 1793448000000;
const historySQL = 'SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10';
const tables = ['users','sessions','refresh_tokens','refresh_rotation_results','recovery_codes','auth_rate_limits',
  'restaurants','dishes','restaurant_search','visits','change_sets','revisions','idempotency_keys','mutation_guards'];
const scenarios = {
  'p1-4500': { selected: '36b96093-b118-4f24-9243-c225d2902229', revisions: 11, dishes: 5, history: 13500,
    fixture: '664bf1e7a42ce7a6c32850a4ba38ef94aafd533eeae2c1b0bef186e972f1bbfc' },
  'plan-6000': { selected: '7900e620-0083-4b1b-90b1-18f0665e228b', revisions: 14, dishes: 8, history: 18000,
    fixture: '5021392ad1d8cec6c9b8a7400cf50b2fe6b0c2e05b2148dfc66e596d52685244' },
};
function check(value, message) {
  if (!value) throw new Error(message);
}
function equal(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
function hash(raw) {
  return Array.from(sha256(typeof raw === 'string' ? encoder.encode(raw) : raw), b => b.toString(16).padStart(2,'0')).join('');
}
function length(raw) { return encoder.encode(raw).byteLength; }
function finite(value, integer = false) {
  check(typeof value === 'number' && Number.isFinite(value) && value >= 0 && (!integer || Number.isInteger(value)), 'invalid metadata/clock');
}
function readonly(result) {
  check(result.success === true, 'D1 read failed');
  for (const key of ['duration','size_after','rows_read','rows_written','last_row_id','changes']) {
    finite(result.meta[key], key !== 'duration');
  }
  check(result.meta.rows_written === 0 && result.meta.changes === 0 && result.meta.changed_db === false, 'writeful read');
}
function uuids(ids) {
  check(ids.length === 10 && new Set(ids).size === 10 && ids.every(id => typeof id === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id)), 'ten distinct revision UUIDs');
}
function leaves(task) {
  if (task.type === 'test') return [{ id: task.id, name: task.name, mode: task.mode, state: task.result?.state ?? null }];
  return task.tasks.flatMap(leaves);
}
function textEvidence(value) {
  check(typeof value === 'string', 'history text missing');
  return { utf16_length: value.length, utf8_length: length(value), sha256: hash(value) };
}
function historyEvidence(row) {
  const diff = JSON.parse(row.diff_json);
  check(Array.isArray(diff) && diff.length === 1 && diff[0].field === 'review' &&
    typeof diff[0].before === 'string' && typeof diff[0].after === 'string', 'long review change missing');
  const json = JSON.stringify(row);
  return { id: row.id, user_id: row.user_id, restaurant_id: row.restaurant_id, entity_type: row.entity_type,
    entity_id: row.entity_id, change_set_id: row.change_set_id, entity_version_before: row.entity_version_before,
    entity_version_after: row.entity_version_after, created_at: row.created_at,
    diff_json: textEvidence(row.diff_json), search_text: textEvidence(row.search_text),
    review_diff: { change_count: diff.length, field: diff[0].field, before_utf16_length: diff[0].before.length,
      after_utf16_length: diff[0].after.length }, row_json_utf8_bytes: length(json), row_sha256: hash(json), column_order: Object.keys(row) };
}

// Read component spans from the one compact JSON byte buffer. ASCII delimiters
// cannot occur inside a UTF-8 multibyte sequence; quoted/escaped bytes are skipped.
function jsonRepresentation(bytes) {
  function end(start) {
    let depth=0, quoted=false;
    for(let i=start;i<bytes.byteLength;i++) {
      const b=bytes[i];
      if(quoted) {
        if(b===92) i++;
        else if(b===34) { quoted=false; if(depth===0) return i+1; }
      } else if(b===34) quoted=true;
      else if(b===123 || b===91) depth++;
      else if(b===125 || b===93) {
        if(depth===0) return i;
        if(--depth===0) return i+1;
      } else if(b===44 && depth===0) return i;
    }
    throw new Error('incomplete JSON component');
  }
  check(bytes[0]===123 && bytes[bytes.byteLength-1]===125, 'complete JSON object');
  const members=[], order=[], rowBytes=[];
  let metaJson;
  for(let cursor=1;cursor<bytes.byteLength-1;) {
    check(bytes[cursor]===34, 'JSON member key');
    const keyEnd=end(cursor);
    const key=JSON.parse(decoder.decode(bytes.subarray(cursor,keyEnd)));
    check(bytes[keyEnd]===58, 'JSON member colon');
    const start=keyEnd+1, stop=end(start);
    order.push(key);
    members.push({key,key_json_utf8_bytes:keyEnd-cursor,value_json_utf8_bytes:stop-start});
    if(key==='meta') metaJson=decoder.decode(bytes.subarray(start,stop));
    if(key==='results') {
      check(bytes[start]===91 && bytes[stop-1]===93, 'JSON results array');
      for(let row=start+1;row<stop-1;) {
        check(bytes[row]===123, 'JSON result row');
        const rowEnd=end(row);
        rowBytes.push(rowEnd-row);
        check(rowEnd===stop-1 || bytes[rowEnd]===44, 'JSON row separator');
        row=rowEnd===stop-1 ? rowEnd : rowEnd+1;
      }
    }
    check(stop===bytes.byteLength-1 || bytes[stop]===44, 'JSON member separator');
    cursor=stop===bytes.byteLength-1 ? stop : stop+1;
  }
  return {property_order:order,members,row_json_utf8_bytes:rowBytes,meta_json:metaJson};
}

// These are explicit original-predicate read-only attestations, always in this order.
async function attestation(context) {
  const { selected, revisions, dishes, history } = context.frozen;
  const reads = [];
  attemptedReads = reads;
  async function read(label, sql, params, summarize = undefined) {
    const attempt = { label, sql, params };
    reads.push(attempt);
    let result = await savedDB.prepare(sql).bind(...params).all();
    Object.assign(attempt,{success:result.success,meta:result.meta});
    readonly(result);
    const results = summarize ? result.results.map(summarize) : result.results;
    attempt.results=results;
    return results;
  }
  const account = await read('user', 'SELECT id,username,display_name FROM users WHERE id=?', [user]);
  check(equal(account, [{id:user,username:'capacity_user',display_name:'Synthetic capacity account'}]), 'wrong account');
  const restaurant = await read('restaurant', context.detail.sql.replace('SELECT *','SELECT id,user_id,version,created_at,updated_at'), context.detail.params);
  check(equal(restaurant, [{id:selected,user_id:user,version:revisions,created_at:fixed+2000,updated_at:fixed+2000+revisions-1}]), 'wrong selected restaurant');
  for (const [label, statement, count] of [['dishes',context.dishes,dishes],['visits',context.visits,3]]) {
    const rows = await read(label, statement.sql.replace('SELECT *','SELECT id,user_id,restaurant_id,version,created_at,updated_at'), statement.params);
    check(rows.length === count && new Set(rows.map((r)=>r.id)).size === count, 'child count/IDs');
    check(rows.every((r)=>r.user_id===user && r.restaurant_id===selected && r.version===2 && r.updated_at===r.created_at+1), 'child identity/version');
  }
  const n = await read('selected_revision_count', 'SELECT COUNT(*) AS n FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL', context.params);
  check(equal(n,[{n:revisions}]), 'selected history count');
  const counts = Object.fromEntries(tables.map(t=>[t,0]));
  Object.assign(counts,{users:1,sessions:1,refresh_tokens:1,restaurants:501,dishes:500*dishes+5,visits:1503,
    restaurant_search:501,revisions:history+10,change_sets:history+1});
  for (const table of tables) {
    check(equal(await read('count:'+table,`SELECT COUNT(*) AS n FROM ${table}`,[]),[{n:counts[table]}]), 'post-suite counts '+table);
  }
  const appended = await read('appended_restaurant','SELECT id,user_id,version,created_at,updated_at FROM restaurants WHERE user_id=? AND created_at=?',[user,fixed+500000]);
  check(appended.length===1 && appended[0].user_id===user && appended[0].version===2 && appended[0].updated_at===fixed+500001, 'completeWrite restaurant');
  const group = await read('appended_revisions','SELECT id,user_id,restaurant_id,entity_type,entity_id,entity_version_after,change_set_id,created_at FROM revisions WHERE user_id=? AND restaurant_id=? ORDER BY created_at,id',[user,appended[0].id]);
  uuids(group.map((r)=>r.id));
  check(new Set(group.map((r)=>r.change_set_id)).size===1 && group.every((r)=>r.user_id===user && r.restaurant_id===appended[0].id), 'completeWrite grouping');
  await read('appended_change_set','SELECT id,user_id,created_at FROM change_sets WHERE user_id=? AND id=?',[user,group[0].change_set_id]);
  const full = await read('history_full',context.full_sql,context.params,historyEvidence);
  const ids = full.map((r)=>r.id);
  uuids(ids);
  for (let i=0;i<10;i++) {
    const r=full[i], version=revisions-i;
    check(r.user_id===user && r.restaurant_id===selected && r.entity_type==='restaurant' && r.entity_id===selected &&
      r.entity_version_after===version && r.entity_version_before===version-1 && r.created_at===fixed+2000+version-1 &&
      r.diff_json.utf16_length>=7800 && r.search_text.utf16_length>=7800, 'history identity/version/content');
    if(i) check(full[i-1].created_at>r.created_at || (full[i-1].created_at===r.created_at && full[i-1].id>r.id), 'history order');
  }
  const idRows = await read('history_id',context.id_sql,context.params);
  check(equal(idRows,ids.map(id=>({id}))), 'ordered id projection differs');
  return {reads};
}
function contextFrom(original) {
  const name = original.scenario?.name;
  const frozen = scenarios[name];
  check(frozen && original.schemaVersion===1 && original.repetitions===30 && original.p95LimitMs===100, 'original emit identity');
  check(original.seed.seed===0x51a71c && original.seed.seedHex==='0x51a71c' && original.seed.fixedTimeUtc==='2026-10-31T12:00:00.000Z' && original.seed.fixtureSha256===frozen.fixture, 'frozen fixture');
  check(original.queries.length===9 && original.actualAPI.length===8 && original.queries.every((q)=>q.samples.length===30) && original.actualAPI.every((q)=>q.samples.length===30), 'original first thirty groups');
  const one = (id) => {
    const q=original.queries.filter((q)=>q.id===id);
    check(q.length===1 && q[0].statements.length===1, 'unique original query');
    return q[0].statements[0];
  };
  const statement=one('entity_revisions_10');
  const params=[user,'restaurant',frozen.selected];
  check(statement.sql===historySQL && equal(statement.params,params), 'exact original emitted history SQL/params');
  const detail=one('restaurant_detail'), dishes=one('restaurant_dishes'), visits=one('restaurant_visits');
  check(detail.sql==='SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL', 'original detail predicate');
  check(dishes.sql==='SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC', 'original dishes predicate');
  check(visits.sql==='SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC', 'original visits predicate');
  for(const s of [detail,dishes,visits]) check(equal(s.params,[user,frozen.selected]), 'selected original predicate binding');
  return {name,frozen,full_sql:statement.sql,id_sql:statement.sql.replace(/^SELECT \*/, 'SELECT id'),params:statement.params,detail,dishes,visits};
}

// Root file afterAll executes in the original test executor, on the saved env.DB.
afterAll(async suite => {
  let preflight;
  let evidence;
  let failure;
  let owned = false;
  let restored = false;
  try {
    const file = suite;
    check(file.file===file && typeof file.filepath==='string' && file.filepath.endsWith('/backend/tests/capacity/capacity_test.ts') && file.pool==='@cloudflare/vitest-pool-workers', 'original root file/pool');
    owned=console.log===capture;
    check(owned && env.DB===savedDB && emitCount===1 && rawEmit!==undefined && ['*','id'].includes(projection), 'capture/binding/projection prerequisites');
    let payload = rawEmit.slice('CAPACITY_RESULT '.length);
    let original = JSON.parse(payload);
    const context=contextFrom(original);
    const task={filepath:file.filepath,pool:file.pool,leaves:leaves(file)};
    const selected=task.leaves.filter((l)=>l.name.startsWith(context.name+': '));
    const other=task.leaves.filter((l)=>!l.name.startsWith(context.name+': '));
    check(task.leaves.length===2 && selected.length===1 && other.length===1 && selected[0].mode==='run' && ['pass','fail'].includes(selected[0].state) && other[0].mode==='skip' && other[0].state==='skip', 'original finished selected leaf');
    const captureEvidence={emit_count:emitCount,unexpected_log_calls:unexpectedLogCalls,wrapper_owned:owned,wrapper_restored:false,
      payload_sha256:hash(payload),payload_utf8_bytes:length(payload),raw_emit_sha256:hash(rawEmit)};
    rawEmit=undefined; payload=undefined; original=undefined;
    const before=await attestation(context);
    preflight={schemaVersion:1,scenario:context.name,projection,original_task:task,capture:captureEvidence,
      binding:{saved_db_same:env.DB===savedDB,provided_names:Object.keys(env).sort()},attestation_before:before};
    async function plans(label) {
      const result = {};
      for(const [arm,sql] of [['full',context.full_sql],['id',context.id_sql]]) {
        const query='EXPLAIN QUERY PLAN '+sql;
        const value=await savedDB.prepare(query).bind(...context.params).all(); readonly(value);
        result[arm]={label:label+':'+arm,sql:query,params:context.params,...value};
      }
      return result;
    }
    const plansBefore=await plans('explain_before');
    const reference=before.reads.find((r)=>r.label==='history_full').results;
    const ids=reference.map((r)=>r.id);
    const rowLengths=Object.fromEntries(reference.map((r)=>[r.id,r.row_json_utf8_bytes]));
    const expectedRowBytes=projection==='*' ? ids.map(id=>rowLengths[id]) : ids.map(id=>length(JSON.stringify({id})));
    const sql=projection==='*' ? context.full_sql : context.id_sql;
    const samples=[];
    evidence={schemaVersion:1,scenario:context.name,projection,original_task:task,
      measurement:{full_sql:context.full_sql,id_sql:context.id_sql,params:context.params,sql},
      plans_before:plansBefore,plans_after:null,attestation_after:null,samples};
    for(let index=1;index<=30;index++) {
      const prepared=savedDB.prepare(sql).bind(...context.params);
      const start=Date.now();
      let result;
      try { result=await prepared.all(); }
      catch(error) { evidence.current_call={index,start_ms:start,stop_ms:Date.now(),error:String(error)}; throw error; }
      const stop=Date.now();
      // Everything below is outside this call's timed await, and affects later allocation.
      evidence.current_call={index,start_ms:start,stop_ms:stop,wall_ms:stop-start,meta:result.meta,returned_count:result.results?.length};
      finite(start,true); finite(stop,true); check(stop>=start,'clock reversal');
      if(samples.length) check(start>=samples[samples.length-1].bookkeeping_stop_ms,'serial clock reversal');
      readonly(result);
      const ordered=result.results.map(r=>r.id);
      check(ordered.length===ids.length && ordered.every((id,i)=>id===ids[i]),'probe ordered identities');
      let json=JSON.stringify(result);
      let bytes=encoder.encode(json);
      const representation=jsonRepresentation(bytes);
      const rowBytes=representation.row_json_utf8_bytes;
      check(rowBytes.length===expectedRowBytes.length && rowBytes.every((size,i)=>size===expectedRowBytes[i]), 'row projection bytes');
      const sample={index,start_ms:start,stop_ms:stop,wall_ms:stop-start,d1_reported_duration_ms:result.meta.duration,
        meta:result.meta,returned_count:result.results.length,ordered_ids:ordered,json_result_utf8_bytes:bytes.byteLength,json_result_sha256:hash(bytes),
        json_representation:representation};
      result=undefined; json=undefined; bytes=undefined;
      samples.push(sample);
      delete evidence.current_call;
      const bookkeepingStop=Date.now();
      check(bookkeepingStop>=stop,'bookkeeping clock reversal');
      sample.bookkeeping_stop_ms=bookkeepingStop; sample.bookkeeping_ms=bookkeepingStop-stop;
    }
    const plansAfter=await plans('explain_after');
    const after=await attestation(context);
    const identity=(a)=>a.reads.map((r)=>({label:r.label,sql:r.sql,params:r.params,results:r.results}));
    check(equal(identity(before),identity(after)) && env.DB===savedDB,'post-probe corpus/binding drift');
    evidence={schemaVersion:1,scenario:context.name,projection,original_task:task,
      measurement:{full_sql:context.full_sql,id_sql:context.id_sql,params:context.params,sql},
      plans_before:plansBefore,plans_after:plansAfter,attestation_after:after,samples};
  } catch(error) {
    failure=error;
  } finally {
    rawEmit=undefined;
    if(console.log===capture) {
      console.log=savedLog;
      restored=console.log===savedLog;
    }
    if(!owned || !restored) failure=failure ?? new Error('console wrapper ownership/restoration failed');
    if(preflight) {
      preflight.capture.wrapper_restored=restored;
      preflight.capture.unexpected_log_calls=unexpectedLogCalls;
      savedLog.call(console,'E2_PREFLIGHT '+JSON.stringify(preflight));
    }
    if(evidence) savedLog.call(console,'E2_HISTORY_RESULT '+JSON.stringify(evidence));
    if(failure) savedLog.call(console,'E2_HISTORY_ERROR '+JSON.stringify({schemaVersion:1,error:String(failure),attempted_reads:attemptedReads,emit_count:emitCount,wrapper_owned:owned,wrapper_restored:restored}));
  }
  if(failure) throw failure;
});
