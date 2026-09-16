"use strict";

const basePath = window.location.pathname.replace(/\/(?:index\.html)?$/, "");
const state = {
  hours: 24,
  rangeMode: "relative",
  customFrom: null,
  customTo: null,
  mismatchLimit: 3,
  sourceNetworkLimit: 3,
  summary: null,
  visibility: {},
  nextCursor: null,
  refreshController: null,
};

const knownLabels = {
  VERIFY: "GraphicalMatrix検証",
  START: "GraphicalMatrix開始拒否",
  CHALLENGE_CREATED: "Challenge作成",
  SELF_SERVICE_AUTH: "自己管理認証",
  CHANGE_SAVE: "GraphicalMatrix変更保存",
  CHANGE_METHOD_SAVE: "MFA方式変更保存",
  TOTP_REGISTER_VERIFY: "TOTP初回登録検証",
};

const exactFilterRules = {
  node: {
    pattern: "[A-Za-z0-9][A-Za-z0-9._-]*",
    maxlength: 64,
    placeholder: "例: idp-node-01",
  },
  event: {
    pattern: "[A-Z][A-Z0-9_]*",
    maxlength: 64,
    placeholder: "例: VERIFY",
  },
  result: {
    pattern: "[A-Z][A-Z0-9_]*",
    maxlength: 64,
    placeholder: "例: FAIL",
  },
  reason: {
    pattern: "[a-z][a-z0-9_]*",
    maxlength: 64,
    placeholder: "例: mismatch",
  },
  user: {
    pattern: "[A-Za-z0-9._@-]{1,255}",
    maxlength: 255,
    placeholder: "例: user001",
  },
  ip: {
    pattern: "[0-9A-Fa-f:.]+(/[0-9]{1,3})?",
    maxlength: 64,
    placeholder: "例: 192.0.2.10",
  },
};

function rangeQuery() {
  if (state.rangeMode === "custom" && state.customFrom && state.customTo) {
    return new URLSearchParams({from: state.customFrom, to: state.customTo});
  }
  const to = new Date();
  const from = new Date(to.getTime() - state.hours * 60 * 60 * 1000);
  return new URLSearchParams({from: from.toISOString(), to: to.toISOString()});
}

async function api(path, params = rangeQuery(), signal = null) {
  const response = await fetch(`${basePath}/api/v1/${path}?${params}`, {
    headers: {"Accept": "application/json"},
    credentials: "same-origin",
    signal,
  });
  if (!response.ok) {
    throw new Error(`${path}: HTTP ${response.status}`);
  }
  return response.json();
}

function text(id, value) {
  document.getElementById(id).textContent = value;
}

function integer(value) {
  return Number(value || 0).toLocaleString("ja-JP");
}

function formatTime(value) {
  if (!value) {
    return "-";
  }
  return new Intl.DateTimeFormat("ja-JP", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
    timeZoneName: "short",
  }).format(new Date(value));
}

function escapeText(value) {
  return String(value ?? "");
}

function eventCount(counts, event, result) {
  return counts
    .filter((item) => item.event === event && (!result || item.result === result))
    .reduce((sum, item) => sum + Number(item.count), 0);
}

function renderSummary(summary) {
  state.summary = summary;
  applyVisibility(summary.visibility || {});
  state.mismatchLimit = Number(summary.topMismatchLimit || state.mismatchLimit);
  state.sourceNetworkLimit =
    Number(summary.topSourceNetworkLimit || state.sourceNetworkLimit);
  text("mismatch-users-heading",
    `入力不一致 上位${integer(state.mismatchLimit)}ユーザー`);
  text("source-network-heading",
    `送信元IP 認証試行 上位${integer(state.sourceNetworkLimit)}件`);
  text("source-network-mismatch-heading",
    `送信元IP 入力不一致 上位${integer(state.sourceNetworkLimit)}件`);
  const counts = summary.counts || [];
  text("metric-success", integer(summary.graphicalMatrixSuccess));
  text("metric-failure", integer(summary.graphicalMatrixFailure));
  text("metric-rate", summary.graphicalMatrixFailureRate == null
    ? "-"
    : `${(summary.graphicalMatrixFailureRate * 100).toFixed(2)}%`);
  text("metric-locked", integer(counts
    .filter((item) => item.result === "LOCKED")
    .reduce((sum, item) => sum + Number(item.count), 0)));
  text("metric-db-error", integer(counts
    .filter((item) => item.result === "DB_ERROR")
    .reduce((sum, item) => sum + Number(item.count), 0)));
  text("metric-self-service", integer(eventCount(counts, "SELF_SERVICE_AUTH", "OK")));
  text("overview-range", `${formatTime(summary.from)} - ${formatTime(summary.to)}`);
  renderMismatchUsers(summary.topMismatchUsers || []);
  renderSourceNetworks(summary.topSourceNetworks || []);
  renderSourceNetworkMismatches(summary.topSourceMismatchNetworks || []);
  if (state.visibility.lockedUsersAvailable === true) {
    renderLockedUsers(
      summary.lockedUsers || [],
      Number(summary.lockedUserCount || 0),
      summary.lockedUsersAsOf);
  } else {
    renderLockedUsersUnavailable();
  }

  const bars = document.getElementById("event-bars");
  bars.replaceChildren();
  const sorted = [...counts].sort((a, b) => Number(b.count) - Number(a.count)).slice(0, 14);
  if (sorted.length === 0) {
    bars.append(emptyMessage());
    return;
  }
  const maximum = Math.max(...sorted.map((item) => Number(item.count)));
  for (const item of sorted) {
    const row = document.createElement("div");
    row.className = "bar-row";
    const label = document.createElement("span");
    label.className = "bar-label";
    label.textContent = `${knownLabels[item.event] || item.event} / ${item.result}`;
    const track = document.createElement("div");
    track.className = "bar-track";
    const fill = document.createElement("div");
    fill.className = "bar-fill";
    fill.style.width = `${Math.max(2, Number(item.count) / maximum * 100)}%`;
    track.append(fill);
    const value = document.createElement("span");
    value.className = "bar-value";
    value.textContent = integer(item.count);
    row.append(label, track, value);
    bars.append(row);
  }
}

function applyVisibility(visibility) {
  state.visibility = visibility;
  const availability = {
    "admin-api": visibility.adminApiAvailable === true,
    ingest: visibility.ingestHealthAvailable === true,
    events: visibility.eventsAvailable === true,
  };
  Object.entries(availability).forEach(([view, available]) => {
    const tab = document.querySelector(`.tab[data-view="${view}"]`);
    tab.classList.toggle("hidden", !available);
    tab.setAttribute("aria-hidden", String(!available));
  });
  const active = document.querySelector(".tab.active");
  if (active && active.classList.contains("hidden")) {
    document.querySelectorAll(".tab").forEach((item) => item.classList.remove("active"));
    document.querySelectorAll(".view").forEach((item) => item.classList.remove("active"));
    document.querySelector('.tab[data-view="overview"]').classList.add("active");
    document.getElementById("view-overview").classList.add("active");
  }
}

function renderMismatchUsers(users) {
  renderCompactTable(
    "mismatch-users",
    ["順位", "ユーザーID", "不一致回数"],
    users.map((item, index) => [
      String(index + 1),
      item.userRef,
      integer(item.count),
    ]));
}

function renderSourceNetworks(networks) {
  renderCompactTable(
    "source-networks",
    ["順位", "IPアドレス", "認証試行回数"],
    networks.map((item, index) => [
      String(index + 1),
      item.sourceNetwork,
      integer(item.count),
    ]));
}

function renderSourceNetworkMismatches(networks) {
  renderCompactTable(
    "source-network-mismatches",
    ["順位", "IPアドレス", "不一致回数"],
    networks.map((item, index) => [
      String(index + 1),
      item.sourceNetwork,
      integer(item.count),
    ]));
}

function renderLockedUsers(users, total, asOf) {
  const suffix = total > users.length ? `（先頭${users.length}件）` : "";
  text("locked-users-count",
    `${integer(total)}件 / ${formatTime(asOf)}時点${suffix}`);
  renderCompactTable(
    "locked-users",
    ["ユーザーID", "Node", "ロック期限"],
    users.map((item) => [
      item.userRef,
      item.nodeId,
      formatTime(item.lockedUntil),
    ]));
}

function renderLockedUsersUnavailable() {
  text("locked-users-count", "この権限では表示できません");
  const target = document.getElementById("locked-users");
  target.replaceChildren();
  const message = document.createElement("div");
  message.className = "empty";
  message.textContent = "現在のロック状態はAUDITOR以上で確認できます。";
  target.append(message);
}

function renderCompactTable(targetId, headers, rows) {
  const target = document.getElementById(targetId);
  target.replaceChildren();
  if (rows.length === 0) {
    target.append(emptyMessage());
    return;
  }
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  const table = document.createElement("table");
  const head = table.createTHead().insertRow();
  headers.forEach((label) => {
    const cell = document.createElement("th");
    cell.textContent = label;
    head.append(cell);
  });
  const body = table.createTBody();
  rows.forEach((values) => {
    const row = body.insertRow();
    values.forEach((value) => {
      const cell = row.insertCell();
      cell.textContent = escapeText(value);
    });
  });
  wrap.append(table);
  target.append(wrap);
}

function emptyMessage() {
  const element = document.createElement("div");
  element.className = "empty";
  element.textContent = "データがありません。収集状態、保持期限、parserエラーも確認してください。";
  return element;
}

function renderEventTable(targetId, events) {
  const target = document.getElementById(targetId);
  target.replaceChildren();
  if (!events || events.length === 0) {
    target.append(emptyMessage());
    return;
  }
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  const table = document.createElement("table");
  const head = table.createTHead().insertRow();
  ["発生時刻", "Node", "Event", "Result", "Reason", "ユーザーID", "IPアドレス"]
    .forEach((label) => {
      const cell = document.createElement("th");
      cell.textContent = label;
      head.append(cell);
    });
  const body = table.createTBody();
  for (const item of events) {
    const row = body.insertRow();
    const values = [
      formatTime(item.occurredAt),
      item.nodeId,
      item.event,
      item.result,
      item.reason,
      item.userRef || "-",
      item.sourceNetwork || "-",
    ];
    values.forEach((value, index) => {
      const cell = row.insertCell();
      cell.textContent = escapeText(value);
      if (index >= 1) {
        cell.classList.add("code");
      }
      if (index === 3) {
        cell.classList.add(item.result === "OK" ? "result-ok" : "result-fail");
      }
    });
  }
  wrap.append(table);
  target.append(wrap);
}

function renderHealth(data) {
  const target = document.getElementById("ingest-table");
  target.replaceChildren();
  const nodes = data.nodes || [];
  if (nodes.length === 0) {
    target.append(emptyMessage());
    setCollectionStateForNodes([]);
    return;
  }
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  const table = document.createElement("table");
  const head = table.createTHead().insertRow();
  ["Node", "状態", "最終受信", "最終イベント", "Accepted", "Duplicate", "Parse failure", "Spool", "Agent"]
    .forEach((label) => {
      const cell = document.createElement("th");
      cell.textContent = label;
      head.append(cell);
    });
  const body = table.createTBody();
  const staleEventSeconds = Number(data.staleEventSeconds || 900);
  const referenceTime = new Date(data.serverTime || Date.now()).getTime();
  for (const node of nodes) {
    const row = body.insertRow();
    const offline = node.agentVersion === "offline-import";
    const delayed = !offline && collectionIsDelayed(
      node.lastReceivedAt, staleEventSeconds, referenceTime);
    const values = [
      node.nodeId,
      offline ? "オフライン" : delayed ? "遅延" : "正常",
      formatTime(node.lastReceivedAt),
      formatTime(node.lastOccurredAt),
      integer(node.acceptedCount),
      integer(node.duplicateCount),
      integer(node.parseFailureCount),
      `${integer(node.agentSpoolBytes)} B`,
      node.agentVersion || "-",
    ];
    values.forEach((value, index) => {
      const cell = row.insertCell();
      cell.textContent = value;
      if (index === 1 && !offline) {
        cell.classList.add(delayed ? "result-fail" : "result-ok");
      }
      if (index === 6
          && Number(node.parseFailureCount) >= Number(data.parseFailureWarningCount || 1)) {
        cell.classList.add("result-fail");
      }
    });
  }
  wrap.append(table);
  target.append(wrap);
  setCollectionStateForNodes(nodes, staleEventSeconds, referenceTime);
}

function collectionIsDelayed(latest, staleEventSeconds, referenceTime) {
  if (!latest) {
    return true;
  }
  const received = new Date(latest).getTime();
  return !Number.isFinite(received)
    || (referenceTime - received) / 1000 > staleEventSeconds;
}

function setCollectionStateForNodes(nodes, staleEventSeconds = 900, referenceTime = Date.now()) {
  const badge = document.getElementById("collection-state");
  badge.className = "status";
  if (!nodes || nodes.length === 0) {
    badge.textContent = "未収集";
    badge.classList.add("status-warning");
    return;
  }
  const liveNodes = nodes.filter((node) => node.agentVersion !== "offline-import");
  if (liveNodes.length === 0) {
    badge.textContent = "オフライン取込";
    badge.classList.add("status-neutral");
    return;
  }
  const delayed = liveNodes.some((node) => collectionIsDelayed(
    node.lastReceivedAt, staleEventSeconds, referenceTime));
  badge.textContent = delayed ? "収集遅延" : "収集中";
  badge.classList.add(delayed ? "status-warning" : "status-good");
}

async function refresh() {
  if (state.refreshController) {
    state.refreshController.abort();
  }
  const controller = new AbortController();
  state.refreshController = controller;
  const error = document.getElementById("error");
  error.classList.add("hidden");
  try {
    const summaryParams = rangeQuery();
    summaryParams.set("topMismatchLimit", String(state.mismatchLimit));
    summaryParams.set("topSourceNetworkLimit", String(state.sourceNetworkLimit));
    const summary = await api("summary", summaryParams, controller.signal);
    renderSummary(summary);
    if (state.visibility.ingestHealthAvailable === true) {
      try {
        renderHealth(await api(
          "ingest-health", new URLSearchParams(), controller.signal));
      } catch (ignored) {
        if (controller.signal.aborted) {
          return;
        }
        setCollectionStateForNodes([]);
      }
    } else {
      setCollectionStateForNodes([]);
    }
    text("last-updated", `更新 ${formatTime(new Date())}`);
    await refreshActiveView(controller.signal);
  } catch (exception) {
    if (exception.name === "AbortError") {
      return;
    }
    error.textContent = `データを取得できません: ${exception.message}`;
    error.classList.remove("hidden");
  } finally {
    if (state.refreshController === controller) {
      state.refreshController = null;
    }
  }
}

async function refreshActiveView(signal = null) {
  const active = document.querySelector(".tab.active").dataset.view;
  if (active === "authentication") {
    const data = await api("authentication", rangeQuery(), signal);
    renderEventTable("authentication-table", data.events);
  } else if (active === "self-service") {
    const data = await api("self-service", rangeQuery(), signal);
    renderEventTable("self-service-table", data.events);
  } else if (active === "admin-api") {
    const data = await api("admin-api", rangeQuery(), signal);
    renderEventTable("admin-api-table", data.events);
  } else if (active === "events") {
    await searchEvents(false, signal);
  }
}

async function searchEvents(append, signal = null) {
  const params = rangeQuery();
  const form = new FormData(document.getElementById("event-filter"));
  const regex = form.get("regex") === "on";
  const field = String(form.get("field") || "node");
  let value = String(form.get("value") || "").trim();
  if (!Object.hasOwn(exactFilterRules, field)) {
    throw new Error("検索項目が不正です");
  }
  if (!regex && (field === "event" || field === "result")) {
    value = value.toUpperCase();
  } else if (!regex && field === "reason") {
    value = value.toLowerCase();
  }
  if (value) {
    params.set(field, value);
  }
  if (regex) {
    params.set("match", "regex");
  }
  params.set("limit", "100");
  if (append && state.nextCursor) {
    params.set("cursor", state.nextCursor);
  }
  const data = await api("events", params, signal);
  if (append) {
    const target = document.getElementById("events-table");
    const existing = [...target.querySelectorAll("tbody tr")];
    renderEventTable("events-table", data.events);
    const body = target.querySelector("tbody");
    for (const row of existing.reverse()) {
      body.prepend(row);
    }
  } else {
    renderEventTable("events-table", data.events);
  }
  state.nextCursor = data.nextCursor;
  document.getElementById("next-events").classList.toggle("hidden", !state.nextCursor);
}

document.querySelectorAll(".tab").forEach((button) => {
  button.addEventListener("click", async () => {
    document.querySelectorAll(".tab").forEach((item) => item.classList.remove("active"));
    document.querySelectorAll(".view").forEach((item) => item.classList.remove("active"));
    button.classList.add("active");
    document.getElementById(`view-${button.dataset.view}`).classList.add("active");
    try {
      await refreshActiveView();
    } catch (exception) {
      const error = document.getElementById("error");
      error.textContent = `データを取得できません: ${exception.message}`;
      error.classList.remove("hidden");
    }
  });
});

document.querySelectorAll("[data-hours]").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".segmented button")
      .forEach((item) => item.classList.remove("active"));
    button.classList.add("active");
    state.hours = Number(button.dataset.hours);
    state.rangeMode = "relative";
    document.getElementById("custom-range-form").classList.add("hidden");
    refresh();
  });
});

function localDateTimeValue(date) {
  const pad = (value) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function parseLocalDateTime(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) {
    return null;
  }
  const [, year, month, day, hour, minute] = match.map(Number);
  const date = new Date(year, month - 1, day, hour, minute, 0, 0);
  if (date.getFullYear() !== year
      || date.getMonth() !== month - 1
      || date.getDate() !== day
      || date.getHours() !== hour
      || date.getMinutes() !== minute) {
    return null;
  }
  return date;
}

document.getElementById("custom-range-toggle").addEventListener("click", () => {
  const form = document.getElementById("custom-range-form");
  const fromInput = document.getElementById("custom-range-from");
  const toInput = document.getElementById("custom-range-to");
  if (!fromInput.value || !toInput.value) {
    const to = new Date();
    const from = new Date(to.getTime() - state.hours * 60 * 60 * 1000);
    fromInput.value = localDateTimeValue(from);
    toInput.value = localDateTimeValue(to);
  }
  document.querySelectorAll(".segmented button")
    .forEach((item) => item.classList.remove("active"));
  document.getElementById("custom-range-toggle").classList.add("active");
  form.classList.remove("hidden");
  fromInput.focus();
});

document.getElementById("custom-range-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) {
    return;
  }
  const from = parseLocalDateTime(
    document.getElementById("custom-range-from").value);
  const to = parseLocalDateTime(
    document.getElementById("custom-range-to").value);
  if (!from || !to || from.getTime() >= to.getTime()) {
    const error = document.getElementById("error");
    error.textContent = "カスタム期間は、開始日時を終了日時より前に設定してください。";
    error.classList.remove("hidden");
    return;
  }
  state.rangeMode = "custom";
  state.customFrom = from.toISOString();
  state.customTo = to.toISOString();
  refresh();
});

document.getElementById("refresh").addEventListener("click", refresh);
document.getElementById("mismatch-limit-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const input = document.getElementById("mismatch-limit");
  if (!input.reportValidity()) {
    return;
  }
  state.mismatchLimit = Number(input.value);
  refresh();
});
document.getElementById("source-network-limit-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const input = document.getElementById("source-network-limit");
  if (!input.reportValidity()) {
    return;
  }
  state.sourceNetworkLimit = Number(input.value);
  refresh();
});
document.getElementById("event-filter").addEventListener("submit", (event) => {
  event.preventDefault();
  searchEvents(false);
});

function updateEventFilterValidation() {
  const field = document.getElementById("event-filter-field").value;
  const input = document.getElementById("event-filter-value");
  const regex = document.querySelector('#event-filter input[name="regex"]').checked;
  const rule = exactFilterRules[field];
  input.placeholder = regex ? "RE2形式の正規表現を入力" : rule.placeholder;
  input.maxLength = regex ? 128 : rule.maxlength;
  if (regex) {
    input.removeAttribute("pattern");
  } else {
    input.setAttribute("pattern", rule.pattern);
  }
}

document.getElementById("event-filter-field")
  .addEventListener("change", updateEventFilterValidation);
document.querySelector('#event-filter input[name="regex"]')
  .addEventListener("change", updateEventFilterValidation);
document.getElementById("next-events").addEventListener("click", () => searchEvents(true));

updateEventFilterValidation();
text("custom-range-timezone",
  `表示時刻: ${Intl.DateTimeFormat().resolvedOptions().timeZone || "local"}`);
refresh();
window.setInterval(refresh, 60000);
