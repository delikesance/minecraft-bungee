export class APIError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(
  url: string,
  method = "GET",
  csrf = "",
  body?: unknown,
): Promise<T> {
  const res = await fetch("/api/v1" + url, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new APIError(
      res.ok
        ? "Invalid server response"
        : "Service temporarily unavailable. Please retry.",
      res.status,
    );
  }
  if (!res.ok) throw new APIError(value.error || "Request failed", res.status);
  return value;
}
