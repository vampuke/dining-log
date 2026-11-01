// Independent Node.js oracle, executed only on GitHub Actions.
// All inputs are public test fixtures, never application credentials.
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { pbkdf2Sync, createHmac } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import process from 'node:process';
import console from 'node:console';
import { URL } from 'node:url';

// RFC 7914 section 11: PBKDF2-HMAC-SHA256, P="passwd", S="salt", c=1, dkLen=64.
assert.equal(
  pbkdf2Sync('passwd', 'salt', 1, 64, 'sha256').toString('hex'),
  '55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783',
);

const saltHex = '000102030405060708090a0b0c0d0e0f';
const pepperHex = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f';
const inputs = [
  { password: 'password', iterations: 10000, saltHex },
  { password: ' 密码 🔒 unchanged ', iterations: 10000, saltHex },
  { password: 'password', iterations: 10000, saltHex: Buffer.from('saltSALTsaltSALT').toString('hex') },
  { password: 'password', iterations: 12000, saltHex },
];
const vectors = inputs.map((input) => {
  const derived = pbkdf2Sync(Buffer.from(input.password, 'utf8'), Buffer.from(input.saltHex, 'hex'), input.iterations, 32, 'sha256');
  return {
    ...input, pepperHex, derivedHex: derived.toString('hex'),
    storedHex: createHmac('sha256', Buffer.from(pepperHex, 'hex')).update(derived).digest('hex'),
  };
});
const fixtureUrl = new URL('./password-vectors.json', import.meta.url);
if (process.argv.includes('--write')) {
  writeFileSync(fixtureUrl, JSON.stringify(vectors, null, 2) + '\n');
} else {
  assert.deepEqual(JSON.parse(readFileSync(fixtureUrl, 'utf8')), vectors);
}
console.log('PASS: RFC 7914 and 4 independent PBKDF2 + HMAC fixtures (3 at 10000 iterations, 1 at 12000)');
