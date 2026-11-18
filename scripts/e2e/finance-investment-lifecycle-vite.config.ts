import { mergeConfig } from 'vite';
import baseConfig from './vite.config';

const gatewayTarget = process.env.FINANCE_API_PROXY_TARGET?.trim();
if (!gatewayTarget) throw new Error('Finance lifecycle runner requires a job-local Gateway target.');

export default mergeConfig(baseConfig, {
  server: {
    proxy: {
      '^/api/v1/auth/finance-reference/login$': {
        target: gatewayTarget,
        changeOrigin: false,
        secure: false,
      },
    },
  },
});
