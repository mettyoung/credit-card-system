// The only file that knows the API: its paths, the placeholder identity headers, and the problem JSON shape.
// A screen calls a function and gets data back, or an ApiError to show - it never builds a URL or reads a status.

const IDENTITY_KEY = 'ccapp.identity';

/** The chosen placeholder identity, {role: 'applicant' | 'reviewer', id}. The only thing kept in the browser. */
export function identity() {
  try {
    return JSON.parse(localStorage.getItem(IDENTITY_KEY));
  } catch {
    return null;
  }
}

export function setIdentity(value) {
  try {
    if (value) {
      localStorage.setItem(IDENTITY_KEY, JSON.stringify(value));
    } else {
      localStorage.removeItem(IDENTITY_KEY);
    }
  } catch {
    // Private windows can refuse storage; the identity then lasts only for this page.
  }
  memory = value;
}

let memory = null;

function currentIdentity() {
  return identity() ?? memory;
}

/** An RFC 9457 problem from the API, or a failure talking to it. `detail` is safe to show as it is. */
export class ApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `Request failed (${status})`);
    this.status = status;
    this.problem = problem ?? {};
  }

  /** The problem type without its prefix, e.g. "version-mismatch". */
  get code() {
    return (this.problem.type || '').replace(/^\/problems\//, '');
  }
}

async function request(method, path, body) {
  const headers = { Accept: 'application/json, application/problem+json' };
  const who = currentIdentity();
  if (who?.role === 'applicant') headers['X-User-Id'] = who.id;
  if (who?.role === 'reviewer') headers['X-Reviewer-Id'] = who.id;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, { detail: 'The server could not be reached. Is it running?' });
  }
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new ApiError(response.status, data);
  }
  return data;
}

// --- applicant ---------------------------------------------------------------------------------------------------

export const listApplications = () => request('GET', '/v1/applications').then((page) => page.items);
export const createDraft = (cardProductCode) => request('POST', '/v1/applications', { cardProductCode });
export const getApplication = (id) => request('GET', `/v1/applications/${id}`);
export const updateDraft = (id, form) => request('PATCH', `/v1/applications/${id}`, form);
export const submit = (id) => request('POST', `/v1/applications/${id}/submit`);

/**
 * FR3 from a browser: hash the file, ask for a pre-signed URL bound to that hash, PUT the bytes straight to the
 * object store with the headers the URL was signed with, then ask the server to verify what arrived.
 *
 * @returns the verdict, {documentId, kind, status: 'UPLOADED' | 'INVALID', reason}
 */
export async function uploadDocument(applicationId, file, kind = 'ID') {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer()));
  const sha256 = [...digest].map((b) => b.toString(16).padStart(2, '0')).join('');

  const upload = await request('POST', `/v1/applications/${applicationId}/documents`, {
    kind,
    contentType: file.type,
    sizeBytes: file.size,
    sha256,
  });

  let put;
  try {
    put = await fetch(upload.url, { method: upload.method, headers: upload.requiredHeaders, body: file });
  } catch {
    throw new ApiError(0, { detail: 'The document store could not be reached.' });
  }
  if (!put.ok) {
    throw new ApiError(put.status, { detail: 'The document store refused the file.' });
  }
  return request('POST', `/v1/applications/${applicationId}/documents/${upload.documentId}/complete`);
}

/**
 * FR11: reads the live timeline with fetch, not EventSource - EventSource cannot send X-User-Id. Calls onEvent for
 * each event as it arrives. Resolves true when the server ends the stream (terminal, or its timeout), and false
 * when the timeline is not available (switched off, or not this applicant's).
 *
 * @param afterSeq the last event already shown, sent as Last-Event-ID so nothing is repeated
 */
export async function streamTimeline(applicationId, afterSeq, onEvent, signal) {
  const headers = { Accept: 'text/event-stream, application/problem+json' };
  const who = currentIdentity();
  if (who?.role === 'applicant') headers['X-User-Id'] = who.id;
  if (afterSeq) headers['Last-Event-ID'] = String(afterSeq);

  const response = await fetch(`/v1/applications/${applicationId}/timeline`, { headers, signal });
  if (response.status === 404) return false;
  if (!response.ok) throw new ApiError(response.status, null);

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return true;
    buffer += value.replace(/\r\n/g, '\n');
    let end;
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      const data = frame.split('\n').filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).replace(/^ /, '')).join('\n');
      if (data) onEvent(JSON.parse(data));
    }
  }
}

// --- reviewer ----------------------------------------------------------------------------------------------------

export const reviewQueue = () => request('GET', '/v1/review/applications');
export const decide = (id, outcome, reason, version) =>
  request('POST', `/v1/review/applications/${id}/decision`, { outcome, reason: reason || null, version });
