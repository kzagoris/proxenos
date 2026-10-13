# Guided setup keeps local progress separate from connection evidence

The desktop GUI now guides tunnel creation, credentials, Workspace registration, ChatGPT plugin
creation and a first Root listing. This expands the original management-only GUI scope. Local
setup progress is stored separately from the Runtime's registrations and catalog acknowledgement;
leaving setup or closing the window changes no Access Level and never stops the Runtime.

Saving credentials replaces the existing file atomically at mode 0600 and starts an absent Runtime.
An attached Runtime must be Stopped explicitly first: silently restarting it would end running
Operations and change exposure without the user's intent. The terminal wizard remains available.

When no saved credentials are available, opening the GUI enters onboarding and only attempts to
attach to an existing Runtime. It waits for Save & connect before starting an absent Runtime,
instead of presenting the Runtime's terminal-wizard startup refusal. Leaving onboarding keeps a
Continue setup action on the absent Runtime screen, so credential creation remains reachable.

Creating a ChatGPT plugin remains the user's word (ADR 0007). Verification instead requires a
successful ChatGPT-origin `list_directory` of the selected Workspace's Root, started after the
verification step began, in the current Runtime start. Frontend Try Operations, earlier Activity,
failures and Undelivered answers do not count. Origin holds no conversation identity, so the GUI
names the observed Operation and its time, without claiming it came from a specific conversation
or that ChatGPT remains able to reach the Runtime. Runtime, Tunnel and Connector keep their
independent readings (ADR 0009).
