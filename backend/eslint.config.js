import tseslint from 'typescript-eslint';
export default tseslint.config(
  { ignores: ['node_modules/**', '.wrangler/**', 'dist/**'] },
  ...tseslint.configs.recommended,
);
