import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';

import { AppRouter } from '@/app/AppRouter';
import { buildAppJournalNewPath } from '@/constants/routes';
import '@/styles/globals.css';

const rootElement = document.getElementById('root');
if (!rootElement) throw new Error('Finance lifecycle harness root is missing.');

window.history.replaceState(null, '', buildAppJournalNewPath('investment'));

createRoot(rootElement).render(
  <StrictMode>
    <BrowserRouter>
      <AppRouter />
    </BrowserRouter>
  </StrictMode>,
);
