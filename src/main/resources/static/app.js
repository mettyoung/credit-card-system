// The screens and a small hash router. Rendering is plain template strings; every value that came from a person or
// the server goes through esc(), so a name like "<script>" is shown as text, never run.
import * as api from './api.js';

const app = document.getElementById('app');
const nav = document.getElementById('nav');

let poller = null;

const STATUS_TEXT = {
  DRAFT: 'Draft — not submitted yet.',
  SUBMITTED: "Received. We're starting your checks.",
  VERIFYING: "We're checking your ID.",
  NEEDS_INFO: 'We need another document from you.',
  CHECKS_COMPLETE: 'Checks complete. A decision is on its way.',
  APPROVED: 'Approved — your card is on its way.',
  REFERRED: 'A member of our team is reviewing your application.',
  DECLINED: "We're unable to offer you a card this time.",
  EXPIRED: "This application expired: we didn't receive the document we asked for.",
};
const FOLLOW = new Set(['SUBMITTED', 'VERIFYING', 'CHECKS_COMPLETE']);
const TERMINAL = new Set(['APPROVED', 'DECLINED', 'EXPIRED']);

const REASON_TEXT = {
  FRAUD_SUSPECTED: 'Identity check flagged suspected fraud',
  EVIDENCE_UNAVAILABLE: 'The identity vendor never answered',
  FRAUD_CONFIRMED: 'Fraud confirmed',
  IDENTITY_NOT_ESTABLISHED: 'Identity could not be confirmed',
  POLICY: 'Other policy reason',
};
const REVIEWER_REASONS = ['FRAUD_CONFIRMED', 'IDENTITY_NOT_ESTABLISHED', 'POLICY'];

// Development only. The Onfido mock picks its answer from the applicant's last name, the way vendor sandboxes key
// off test names, so a scenario is a last name. Choosing one fills the field in, visibly - nothing is hidden.
const SCENARIOS = [
  { lastName: '', label: 'None — use the last name I type (it will verify)' },
  { lastName: 'Tan', label: 'Approved — clear result' },
  { lastName: 'Caution', label: 'Approved — an answer with a caveat' },
  { lastName: 'Fraud', label: 'Referred to review — suspected fraud' },
  { lastName: 'Blurry', label: 'Asks for another ID once, then approved' },
  { lastName: 'Unreadable', label: 'Asks for another ID every time' },
  { lastName: 'Unavailable', label: 'Referred to review — the vendor is down (about a minute)' },
  { lastName: 'Flaky', label: 'Approved after the vendor fails twice' },
  { lastName: 'Lostreply', label: "Approved after the vendor's reply is lost (about 25 s)" },
];
const scenarioOf = (lastName) => SCENARIOS.find((s) => s.lastName && s.lastName === lastName);

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function errorBox(error) {
  if (!error) return '';
  const missing = error.problem?.missing ? `<br>Missing: ${error.problem.missing.map(esc).join(', ')}` : '';
  const reload = error.code === 'version-mismatch'
    ? ' <button type="button" class="link" data-action="reload">Reload the latest</button>' : '';
  return `<p class="error" role="alert">${esc(error.message)}${missing}${reload}</p>`;
}

function statusBadge(status) {
  return `<span class="badge badge-${esc(status).toLowerCase()}">${esc(status.replace('_', ' ').toLowerCase())}</span>`;
}

function stopPolling() {
  if (poller) clearInterval(poller);
  poller = null;
}

function renderNav() {
  const who = api.identity();
  if (!who) {
    nav.innerHTML = '';
    return;
  }
  const links = who.role === 'applicant'
    ? '<a href="#/applications">My applications</a>'
    : '<a href="#/review">Review queue</a>';
  nav.innerHTML = `${links}
    <span class="who">${esc(who.role)}: <strong>${esc(who.id)}</strong></span>
    <button type="button" class="link" id="switch">Switch</button>`;
  document.getElementById('switch').onclick = () => {
    api.setIdentity(null);
    location.hash = '#/who';
  };
}

// --- who are you? ------------------------------------------------------------------------------------------------

function whoScreen() {
  app.innerHTML = `
    <section class="card">
      <h1>Who are you?</h1>
      <p class="muted">This proof of concept has no sign-in yet. Pick an id — it is sent as the
        <code>X-User-Id</code> or <code>X-Reviewer-Id</code> placeholder header, exactly as the API expects.</p>
      <div class="split">
        <form id="applicant" class="stack">
          <h2>Applicant</h2>
          <label>Your id <input name="id" required autocomplete="off" value="applicant-1"></label>
          <button>Continue as applicant</button>
        </form>
        <form id="reviewer" class="stack">
          <h2>Reviewer</h2>
          <label>Reviewer id <input name="id" required autocomplete="off" value="reviewer-1"></label>
          <button>Continue as reviewer</button>
        </form>
      </div>
    </section>`;
  for (const role of ['applicant', 'reviewer']) {
    document.getElementById(role).onsubmit = (event) => {
      event.preventDefault();
      api.setIdentity({ role, id: event.target.id.value.trim() });
      location.hash = role === 'applicant' ? '#/applications' : '#/review';
    };
  }
}

// --- my applications ---------------------------------------------------------------------------------------------

async function applicationsScreen(error) {
  let items = [];
  try {
    items = await api.listApplications();
  } catch (e) {
    error = e;
  }
  const rows = items.map((a) => `
    <tr>
      <td>${esc(a.cardProductCode)}</td>
      <td>${esc(a.lastName) || '<span class="muted">—</span>'}</td>
      <td>${statusBadge(a.status)}</td>
      <td><a href="#/applications/${esc(a.id)}">Open</a></td>
    </tr>`).join('');
  app.innerHTML = `
    <section class="card">
      <h1>My applications</h1>
      ${errorBox(error)}
      <form id="new" class="inline">
        <label>Card <select name="product"><option>CLASSIC</option><option>PLATINUM</option></select></label>
        <button>Start a new application</button>
      </form>
      ${items.length ? `<table><thead><tr><th>Card</th><th>Last name</th><th>Status</th><th></th></tr></thead>
        <tbody>${rows}</tbody></table>` : '<p class="muted">No applications yet.</p>'}
    </section>`;
  document.getElementById('new').onsubmit = async (event) => {
    event.preventDefault();
    try {
      const created = await api.createDraft(event.target.product.value);
      location.hash = `#/applications/${created.id}`;
    } catch (e) {
      // One draft per card: the API names the existing one, so open it instead of failing.
      if (e.problem?.applicationId) {
        location.hash = `#/applications/${e.problem.applicationId}`;
      } else {
        applicationsScreen(e);
      }
    }
  };
}

// --- one application ---------------------------------------------------------------------------------------------

async function applicationScreen(id, error, notice) {
  let application;
  try {
    application = await api.getApplication(id);
  } catch (e) {
    app.innerHTML = `<section class="card"><h1>Application</h1>${errorBox(e)}
      <a href="#/applications">Back to my applications</a></section>`;
    return;
  }
  if (application.status === 'DRAFT') {
    draftView(application, error, notice);
  } else {
    statusView(application, error, notice);
  }
}

function draftView(a, error, notice) {
  app.innerHTML = `
    <section class="card">
      <p><a href="#/applications">← My applications</a></p>
      <h1>${esc(a.cardProductCode)} card application</h1>
      ${errorBox(error)}
      ${notice ? `<p class="notice" role="status">${esc(notice)}</p>` : ''}
      <form id="details" class="stack">
        <h2>1. About you</h2>
        <div class="grid">
          <label>First name <input name="firstName" value="${esc(a.firstName)}" autocomplete="given-name"></label>
          <label>Last name <input name="lastName" value="${esc(a.lastName)}" autocomplete="family-name"
            ${scenarioOf(a.lastName) ? 'readonly' : ''}></label>
          <label>Date of birth <input name="dateOfBirth" type="date" value="${esc(a.dateOfBirth)}"></label>
          <label>Country <input name="country" value="${esc(a.country)}" maxlength="2" placeholder="SG"></label>
        </div>
        <label class="test-field">Identity vendor response <span class="tag">development only</span>
          <select name="scenario">
            ${SCENARIOS.map((sc) => `<option value="${esc(sc.lastName)}"
              ${(scenarioOf(a.lastName)?.lastName ?? '') === sc.lastName ? 'selected' : ''}>${esc(sc.label)}</option>`).join('')}
          </select>
          <span class="hint">The mock vendor answers by last name, so a scenario sets the last name for you.</span>
        </label>
        <button>Save details</button>
      </form>
      ${uploadForm('2. Your ID document')}
      <div class="stack">
        <h2>3. Submit</h2>
        <p class="muted">Submit once your details are saved and your ID is uploaded.</p>
        <button type="button" id="submit" class="primary">Submit application</button>
      </div>
    </section>`;

  const details = document.getElementById('details');
  details.scenario.onchange = () => {
    const chosen = details.scenario.value;
    details.lastName.readOnly = Boolean(chosen);
    if (chosen) details.lastName.value = chosen;
  };
  details.onsubmit = async (event) => {
    event.preventDefault();
    const f = event.target;
    try {
      await api.updateDraft(a.id, {
        firstName: f.firstName.value, lastName: f.lastName.value,
        dateOfBirth: f.dateOfBirth.value || null, country: f.country.value, version: a.version,
      });
      applicationScreen(a.id, null, 'Details saved.');
    } catch (e) {
      draftView(a, e);
    }
  };
  wireUpload(a);
  document.getElementById('submit').onclick = async () => {
    try {
      await api.submit(a.id);
      applicationScreen(a.id, null, 'Submitted.');
    } catch (e) {
      applicationScreen(a.id, e);
    }
  };
  wireReload(a.id);
}

function uploadForm(title, accepted = ['ID']) {
  return `
    <form id="upload" class="stack">
      <h2>${esc(title)}</h2>
      <label>${accepted.map(esc).join(' or ')} document (JPEG, PNG or PDF, up to 10 MB)
        <input name="file" type="file" accept="image/jpeg,image/png,application/pdf" required></label>
      <button>Upload</button>
      <p id="upload-result" aria-live="polite"></p>
    </form>`;
}

function wireUpload(a, kind = 'ID', after) {
  document.getElementById('upload').onsubmit = async (event) => {
    event.preventDefault();
    const result = document.getElementById('upload-result');
    const file = event.target.file.files[0];
    result.className = 'muted';
    result.textContent = 'Uploading…';
    try {
      const verdict = await api.uploadDocument(a.id, file, kind);
      if (verdict.status === 'UPLOADED') {
        result.className = 'ok';
        result.textContent = 'Uploaded and verified.';
        after?.();
      } else {
        result.className = 'error';
        result.textContent = 'The file did not match what was declared. Please try again.';
      }
    } catch (e) {
      result.className = 'error';
      result.textContent = e.message;
    }
  };
}

function statusView(a, error, notice) {
  const requirements = (a.requirements || []).map((r) => `
    <li>${esc(r.type.toLowerCase())}: ${statusBadge(r.status)}</li>`).join('');
  const wanted = (a.requirements || []).find((r) => r.acceptedDocumentKinds);
  app.innerHTML = `
    <section class="card">
      <p><a href="#/applications">← My applications</a></p>
      <h1>${esc(a.cardProductCode)} card application</h1>
      ${errorBox(error)}
      ${notice ? `<p class="notice" role="status">${esc(notice)}</p>` : ''}
      ${scenarioOf(a.lastName) ? `<p class="test-field">Test scenario: ${esc(scenarioOf(a.lastName).label)}
        <span class="tag">development only</span></p>` : ''}
      <div class="outcome outcome-${esc(a.status).toLowerCase()}" role="status" aria-live="polite">
        <p class="big">${esc(STATUS_TEXT[a.status] || a.status)}</p>
        ${FOLLOW.has(a.status) ? '<p class="muted">This page updates by itself.</p>' : ''}
      </div>
      ${requirements ? `<h2>Checks</h2><ul class="checks">${requirements}</ul>` : ''}
      ${wanted ? uploadForm('Upload another document', wanted.acceptedDocumentKinds) : ''}
    </section>`;

  if (wanted) {
    wireUpload(a, wanted.acceptedDocumentKinds[0], () => setTimeout(() => applicationScreen(a.id), 800));
  }
  if (FOLLOW.has(a.status) && !TERMINAL.has(a.status)) {
    poller = setInterval(async () => {
      try {
        const latest = await api.getApplication(a.id);
        if (latest.status !== a.status || JSON.stringify(latest.requirements) !== JSON.stringify(a.requirements)) {
          stopPolling();
          statusView(latest);
        }
      } catch {
        // A missed poll is not worth an error on screen; the next one tries again.
      }
    }, 3000);
  }
}

function wireReload(id) {
  const button = app.querySelector('[data-action="reload"]');
  if (button) button.onclick = () => applicationScreen(id);
}

// --- review queue ------------------------------------------------------------------------------------------------

async function reviewScreen(error, notice) {
  let items = [];
  try {
    items = await api.reviewQueue();
  } catch (e) {
    error = e;
  }
  const rows = items.map((item) => `
    <li class="review ${item.overdue ? 'overdue' : ''}">
      <div>
        <strong>${esc(item.cardProductCode)}</strong>
        ${item.overdue ? '<span class="badge badge-declined">overdue</span>' : ''}
        <p class="muted">${esc(REASON_TEXT[item.decisionReason] || item.decisionReason)} ·
          referred ${item.referredAt ? esc(new Date(item.referredAt).toLocaleString()) : '—'}</p>
        <p class="muted mono">${esc(item.id)}</p>
      </div>
      <form class="decide inline" data-id="${esc(item.id)}" data-version="${esc(item.version)}">
        <button name="approve" class="primary">Approve</button>
        <select name="reason" aria-label="Decline reason">
          ${REVIEWER_REASONS.map((r) => `<option value="${r}">${esc(REASON_TEXT[r])}</option>`).join('')}
        </select>
        <button name="decline" class="danger">Decline</button>
      </form>
    </li>`).join('');
  app.innerHTML = `
    <section class="card">
      <h1>Review queue</h1>
      ${errorBox(error)}
      ${notice ? `<p class="notice" role="status">${esc(notice)}</p>` : ''}
      ${items.length ? `<ul class="queue">${rows}</ul>` : '<p class="muted">Nothing is waiting for review.</p>'}
      <button type="button" id="refresh" class="link">Refresh</button>
    </section>`;
  document.getElementById('refresh').onclick = () => reviewScreen();
  for (const form of app.querySelectorAll('form.decide')) {
    form.onsubmit = async (event) => {
      event.preventDefault();
      const declining = event.submitter?.name === 'decline';
      try {
        await api.decide(form.dataset.id, declining ? 'DECLINED' : 'APPROVED',
          declining ? form.reason.value : null, Number(form.dataset.version));
        reviewScreen(null, declining ? 'Declined.' : 'Approved.');
      } catch (e) {
        reviewScreen(e);
      }
    };
  }
}

// --- router ------------------------------------------------------------------------------------------------------

function route() {
  stopPolling();
  renderNav();
  const who = api.identity();
  const hash = location.hash || '#/';
  if (!who && hash !== '#/who') {
    location.hash = '#/who';
    return;
  }
  const one = hash.match(/^#\/applications\/([0-9a-f-]+)$/i);
  if (hash === '#/who') return whoScreen();
  if (hash === '#/review' && who.role === 'reviewer') return reviewScreen();
  if (one && who.role === 'applicant') return applicationScreen(one[1]);
  if (hash === '#/applications' && who.role === 'applicant') return applicationsScreen();
  location.hash = who.role === 'reviewer' ? '#/review' : '#/applications';
}

window.addEventListener('hashchange', route);
route();
