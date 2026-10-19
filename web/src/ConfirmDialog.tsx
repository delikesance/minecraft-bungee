import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
} from "react";

type Options = { title?: string; confirmLabel?: string };
type Confirm = (message: string, options?: Options) => Promise<boolean>;
type Request = Options & { message: string; resolve: (value: boolean) => void };
const Context = createContext<Confirm | null>(null);

export function useConfirm() {
  const confirm = useContext(Context);
  if (!confirm) throw new Error("Confirmation provider is missing");
  return confirm;
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<Request | null>(null);
  const pending = useRef<Request[]>([]);
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const messageId = useId();
  const confirm = useCallback<Confirm>((message, options = {}) => {
    return new Promise((resolve) => {
      const next = { ...options, message, resolve };
      pending.current.push(next);
      if (pending.current.length === 1) setRequest(next);
    });
  }, []);

  function finish(accepted: boolean) {
    // Close first so the browser restores focus to the original control.
    dialog.current?.close();
    const current = pending.current.shift();
    setRequest(pending.current[0] || null);
    current?.resolve(accepted);
  }

  useEffect(() => {
    if (request) {
      dialog.current?.showModal();
      cancel.current?.focus();
      document.body.classList.add("confirmation-open");
    }
    return () => document.body.classList.remove("confirmation-open");
  }, [request]);

  useEffect(
    () => () => {
      pending.current.splice(0).forEach((item) => item.resolve(false));
    },
    [],
  );

  return (
    <Context.Provider value={confirm}>
      {children}
      <dialog
        ref={dialog}
        className="confirmation-dialog"
        aria-labelledby={titleId}
        aria-describedby={messageId}
        onCancel={(event) => {
          event.preventDefault();
          finish(false);
        }}
      >
        {request && (
          <>
            <div className="confirmation-mark" aria-hidden="true">
              ✦
            </div>
            <span className="eyebrow">NETWORK CONTROL</span>
            <h2 id={titleId}>{request.title || "Confirm this action"}</h2>
            <p id={messageId}>{request.message}</p>
            <div className="confirmation-actions">
              <button ref={cancel} onClick={() => finish(false)}>
                Cancel
              </button>
              <button className="primary" onClick={() => finish(true)}>
                {request.confirmLabel || "Confirm"}
                <span aria-hidden="true">→</span>
              </button>
            </div>
          </>
        )}
      </dialog>
    </Context.Provider>
  );
}
