import { z } from 'zod';
import weakPasswords from './weak-passwords.json';
export const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
export const username = z.string().max(256).transform(v => v.normalize('NFKC').trim().toLowerCase()).pipe(z.string().regex(/^[a-z][a-z0-9_]{2,31}$/));
const passwordInput = z.string().max(512).refine(v => new TextEncoder().encode(v).length <= 512);
export const newPassword = passwordInput.refine(v => {
  const length = Array.from(v).length;
  return length >= 12 && length <= 128 && v.trim().length > 0 && !v.includes('\0') && !weakPasswords.includes(v.toLowerCase());
}, 'Use 12–128 characters, not an all-whitespace or common password.');
const device = z.string().trim().min(1).max(80);
export const registerSchema = z.object({ username, password: newPassword, display_name: z.string().trim().min(1).refine(v => Array.from(v).length <= 50).optional(), device_name: device }).strict();
export const loginSchema = z.object({ username, password: passwordInput, device_name: device }).strict();
export const refreshSchema = z.object({ refresh_token: z.string().max(128) }).strict();
export const recoverSchema = z.object({ username, recovery_code: z.string().max(128), new_password: newPassword, device_name: device }).strict();
export const passwordSchema = z.object({ current_password: passwordInput, new_password: newPassword, device_name: device }).strict();
