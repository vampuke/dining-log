import { Hono } from 'hono';
import type { AppEnv } from '../env';
import { notImplemented } from './placeholder';

export const auth = new Hono<AppEnv>();
auth.post('/auth/register', notImplemented);
auth.post('/auth/login', notImplemented);
auth.post('/auth/refresh', notImplemented);
auth.post('/auth/logout', notImplemented);
auth.post('/auth/logout-all', notImplemented);
auth.post('/auth/password', notImplemented);
auth.post('/auth/recover', notImplemented);
auth.post('/auth/recovery-codes', notImplemented);
auth.get('/auth/sessions', notImplemented);
auth.delete('/auth/sessions/:session_id', notImplemented);
auth.get('/me', notImplemented);
auth.patch('/me', notImplemented);
auth.delete('/me', notImplemented);
