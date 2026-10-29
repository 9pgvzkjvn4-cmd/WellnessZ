import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { BffAuthClient } from './auth/bffAuthClient';
import './styles.css';

const root = document.getElementById('root');
if (!root) throw new Error('root element missing');
createRoot(root).render(
  <StrictMode>
    <App client={new BffAuthClient()} />
  </StrictMode>,
);
