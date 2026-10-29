import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import { defineConfig } from 'eslint/config';

export default defineConfig(
  { ignores: ['dist', 'ios', 'android', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // PHI protection: never log from application code. Use src/security/log.ts.
      'no-console': 'error',
      'no-restricted-globals': [
        'error',
        { name: 'localStorage', message: 'Never persist tokens/PHI in localStorage. Use SecureStorage.' },
        { name: 'sessionStorage', message: 'Never persist tokens/PHI in sessionStorage. Use SecureStorage.' },
      ],
    },
  },
  {
    files: ['src/security/log.ts'],
    rules: { 'no-console': 'off' },
  },
  {
    files: ['public/sw.js'],
    languageOptions: { globals: globals.serviceworker },
  },
  {
    files: ['vite.config.ts', 'capacitor.config.ts', 'eslint.config.js'],
    languageOptions: { globals: globals.node },
  },
);
