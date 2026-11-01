import { notImplemented } from './placeholder';

import { auth } from '../auth/routes';
export { auth };
auth.post('/auth/logout-all', notImplemented);
auth.post('/auth/recovery-codes', notImplemented);
auth.get('/me', notImplemented);
auth.patch('/me', notImplemented);
auth.delete('/me', notImplemented);
