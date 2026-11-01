import { describe, expect, it } from 'vitest';
import app from '../src';

// Every method/path is copied from PLAN.md 4.3-4.8; P2 promotes auth routes.
const routes = [
  [
    "POST",
    "/api/v1/auth/register"
  ],
  [
    "POST",
    "/api/v1/auth/login"
  ],
  [
    "POST",
    "/api/v1/auth/refresh"
  ],
  [
    "POST",
    "/api/v1/auth/logout"
  ],
  [
    "POST",
    "/api/v1/auth/logout-all"
  ],
  [
    "POST",
    "/api/v1/auth/password"
  ],
  [
    "POST",
    "/api/v1/auth/recover"
  ],
  [
    "POST",
    "/api/v1/auth/recovery-codes"
  ],
  [
    "GET",
    "/api/v1/auth/sessions"
  ],
  [
    "DELETE",
    "/api/v1/auth/sessions/00000000-0000-4000-8000-000000000001"
  ],
  [
    "GET",
    "/api/v1/me"
  ],
  [
    "PATCH",
    "/api/v1/me"
  ],
  [
    "DELETE",
    "/api/v1/me"
  ],
  [
    "POST",
    "/api/v1/restaurants"
  ],
  [
    "GET",
    "/api/v1/restaurants"
  ],
  [
    "GET",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001"
  ],
  [
    "PATCH",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001"
  ],
  [
    "DELETE",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001"
  ],
  [
    "POST",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/restore"
  ],
  [
    "POST",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/dishes"
  ],
  [
    "GET",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/dishes"
  ],
  [
    "GET",
    "/api/v1/dishes/00000000-0000-4000-8000-000000000001"
  ],
  [
    "PATCH",
    "/api/v1/dishes/00000000-0000-4000-8000-000000000001"
  ],
  [
    "DELETE",
    "/api/v1/dishes/00000000-0000-4000-8000-000000000001"
  ],
  [
    "POST",
    "/api/v1/dishes/00000000-0000-4000-8000-000000000001/restore"
  ],
  [
    "POST",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/visits"
  ],
  [
    "GET",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/visits"
  ],
  [
    "GET",
    "/api/v1/visits"
  ],
  [
    "GET",
    "/api/v1/visits/00000000-0000-4000-8000-000000000001"
  ],
  [
    "PATCH",
    "/api/v1/visits/00000000-0000-4000-8000-000000000001"
  ],
  [
    "DELETE",
    "/api/v1/visits/00000000-0000-4000-8000-000000000001"
  ],
  [
    "POST",
    "/api/v1/visits/00000000-0000-4000-8000-000000000001/restore"
  ],
  [
    "POST",
    "/api/v1/revisions"
  ],
  [
    "GET",
    "/api/v1/revisions"
  ],
  [
    "GET",
    "/api/v1/restaurants/00000000-0000-4000-8000-000000000001/revisions"
  ],
  [
    "GET",
    "/api/v1/dishes/00000000-0000-4000-8000-000000000001/revisions"
  ],
  [
    "GET",
    "/api/v1/visits/00000000-0000-4000-8000-000000000001/revisions"
  ],
  [
    "GET",
    "/api/v1/revisions/00000000-0000-4000-8000-000000000001"
  ],
  [
    "PATCH",
    "/api/v1/revisions/00000000-0000-4000-8000-000000000001"
  ],
  [
    "DELETE",
    "/api/v1/revisions/00000000-0000-4000-8000-000000000001"
  ],
  [
    "POST",
    "/api/v1/revisions/00000000-0000-4000-8000-000000000001/restore"
  ],
  [
    "GET",
    "/api/v1/search"
  ]
] as const;

describe('versioned routing', () => {
  it('returns the PLAN health response without querying D1', async () => {
    const res = await app.request('/api/v1/health');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'ok', api_version: 'v1' });
  });
  it.each(routes)('checks %s %s routing and error semantics', async (method, path) => {
    const response = await app.request(path, { method });
    const promotedWrites = ['/api/v1/auth/register', '/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout', '/api/v1/auth/password', '/api/v1/auth/recover'];
    const promotedAccess = ['/api/v1/auth/sessions', '/api/v1/auth/sessions/00000000-0000-4000-8000-000000000001'];
    const status = promotedWrites.includes(path) ? 415 : promotedAccess.includes(path) ? 401 : 501;
    expect(response.status).toBe(status);
    const body = await response.json() as { error: { code: string; message: string } };
    expect(body.error.code).toBe(status === 415 ? 'UNSUPPORTED_MEDIA_TYPE' : status === 401 ? 'SESSION_REVOKED' : 'NOT_IMPLEMENTED');
    expect(body.error.message.length).toBeGreaterThan(0);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  });
  it('does not expose a global dishes list or an unversioned API', async () => {
    expect((await app.request('/api/v1/dishes')).status).toBe(404);
    expect((await app.request('/health')).status).toBe(404);
  });
});
