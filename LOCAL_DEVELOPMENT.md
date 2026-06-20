# Local development

1. Open `~/Development/7E-WFGPT`.
2. Double-click `DEV_START.command`.
3. Use the application in your browser at `http://127.0.0.1:5173`.
4. Choose a synthetic identity, enter property and owner references, and test the Key Handover flow.
5. Double-click `DEV_STOP.command` when you are finished.
6. Use `DEV_STATUS.command` to see whether the frontend and backend are running, and where their logs and data are stored.
7. Use `DEV_RESET_DATA.command` only when you need a clean synthetic test dataset.
8. Run `DEV_VALIDATE.command` before approving a change.
9. Do not push to GitHub until manual testing is approved.

The local backend uses `http://127.0.0.1:8080`, and the React development server proxies `/api` calls there. Local synthetic state and audit history are stored under `.local-dev/data` and survive restarts.
