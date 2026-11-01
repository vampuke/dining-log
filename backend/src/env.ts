export interface Bindings {
  DB: D1Database;
  JWT_HS256_KEYS: string;
  PASSWORD_PEPPERS: string;
  REFRESH_RETRY_KEYS: string;
  RATE_LIMIT_HMAC_KEY: string;
  AUTH_ISSUER: string;
  AUTH_AUDIENCE: string;
  JWT_ACTIVE_KID: string;
  PASSWORD_ACTIVE_PEPPER: string;
  PASSWORD_ITERATIONS: string;
  REFRESH_RETRY_KEY_ID: string;
  REGISTRATION_ENABLED: string;
  APP_VERSION_CODE?: string;
  APP_VERSION_NAME?: string;
  APP_DOWNLOAD_URL?: string;
  APP_APK_SHA256?: string;
  APP_RELEASE_NOTES?: string;
  APP_RELEASED_AT?: string;
}
export type AppEnv = { Bindings: Bindings; Variables: { userId: string } };
