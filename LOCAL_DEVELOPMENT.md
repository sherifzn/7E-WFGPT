# Local development

1. Run `cd /Users/sherifzn/Development/7E-WFGPT`.
2. Run `npm run dev`.
3. Use the application in your browser at `http://127.0.0.1:5173`.
4. Choose a synthetic identity, enter property and owner references, and test the Key Handover flow.
5. Press `Ctrl+C` to stop both services.
6. Run `npm run dev:check` to check the frontend, backend, and Vite proxy.
7. Run `npm run dev:reset-data` only when you need a clean synthetic test dataset.
8. Run `DEV_VALIDATE.command` before approving a change.
9. Do not push to GitHub until manual testing is approved.

The local backend uses `http://127.0.0.1:8080`, and the React development server proxies `/api` calls there. Local synthetic state and audit history are stored under `.local-dev/data` and survive restarts.
