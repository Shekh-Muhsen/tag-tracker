(() => {
  const $ = (id) => document.getElementById(id);
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch {} },
  };

  const map = L.map('map', { preferCanvas: true, zoomControl: false }).setView([20, 0], 2);
  L.control.zoom({ position: 'topright' }).addTo(map);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; OpenStreetMap contributors',
  }).addTo(map);

  const layers = { track: L.layerGroup().addTo(map), points: L.layerGroup().addTo(map), live: L.layerGroup().addTo(map), play: L.layerGroup().addTo(map) };
  let devices = [], deviceId = store.get('device'), range = { h: store.get('range') || '168' };
  let points = [], fitted = false, playTimer = null;

  async function api(path, opts = {}) {
    const r = await fetch(path, { credentials: 'same-origin', ...opts });
    if (r.status === 401) { location.href = '/login'; throw new Error('logged out'); }
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || r.statusText);
    return r.json();
  }

  const fmt = (ts) => new Date(ts * 1000).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
  function ago(ts) {
    const s = Date.now() / 1000 - ts;
    if (s < 90) return 'just now';
    if (s < 3600) return `${Math.round(s / 60)} min ago`;
    if (s < 86400) return `${Math.round(s / 3600)} h ago`;
    return `${Math.round(s / 86400)} days ago`;
  }
  function km(a, b) {
    const R = 6371, r = Math.PI / 180;
    const dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  function currentRange() {
    const now = Math.floor(Date.now() / 1000);
    if (range.h === 'today') { const d = new Date(); d.setHours(0, 0, 0, 0); return [Math.floor(d / 1000), now]; }
    if (range.h === 'custom') return [range.start, range.end];
    return [now - Number(range.h) * 3600, now];
  }

  // ---------- devices ----------
  async function loadDevices() {
    const data = await api('/api/devices');
    devices = data.devices;
    const sel = $('device');
    sel.innerHTML = devices.length ? devices.map((d) => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('')
      : '<option value="">No tags yet – waiting for first check</option>';
    if (!devices.find((d) => d.id === deviceId)) deviceId = devices[0]?.id || null;
    if (deviceId) sel.value = deviceId;
    renderLatest();
    const p = data.poller;
    $('pollStatus').textContent = !p.enabled ? 'Automatic checking is off on this server.'
      : p.running ? 'Checking Google for new locations…'
      : p.last_error ? `Last check failed: ${p.last_error}`
      : p.last_run ? `Last checked ${ago(p.last_run)} · every ${p.interval_minutes} min` : 'Starting…';
  }

  function renderLatest() {
    const d = devices.find((x) => x.id === deviceId);
    const el = $('latest');
    layers.live.clearLayers();
    if (!d) { el.innerHTML = '<span class="muted">No tag selected.</span>'; return; }
    if (d.last_ts == null) {
      el.innerHTML = `<b>${esc(d.name)}</b><br><span class="muted">No locations stored yet.</span>`
        + (d.last_error ? `<br><span class="error">${esc(d.last_error)}</span>` : '');
      return;
    }
    el.innerHTML = `<b>${esc(d.name)}</b><br>Last seen ${ago(d.last_ts)} · ${fmt(d.last_ts)}<br>`
      + `<span class="muted">${d.point_count.toLocaleString()} locations stored in total</span>`
      + (d.last_error ? `<br><span class="error small">${esc(d.last_error)}</span>` : '');
    const ll = [d.last_lat, d.last_lon];
    if (d.last_accuracy) L.circle(ll, { radius: d.last_accuracy, color: d.color, weight: 1, fillOpacity: 0.12 }).addTo(layers.live);
    L.circleMarker(ll, { radius: 9, color: '#fff', weight: 3, fillColor: d.color, fillOpacity: 1 })
      .bindPopup(`<div class="popup"><b>${esc(d.name)} – latest</b>${fmt(d.last_ts)}<br>`
        + `<a target="_blank" rel="noopener" href="https://www.google.com/maps?q=${d.last_lat},${d.last_lon}">Open in Google Maps</a></div>`)
      .addTo(layers.live);
  }

  // ---------- history ----------
  async function loadHistory() {
    stopPlay();
    layers.track.clearLayers(); layers.points.clearLayers(); layers.play.clearLayers();
    if (!deviceId) { points = []; renderStats(); return; }
    const [start, end] = currentRange();
    const data = await api(`/api/history/${encodeURIComponent(deviceId)}?start=${start}&end=${end}`);
    points = data.points.filter((p) => p.lat != null);
    const color = devices.find((d) => d.id === deviceId)?.color || '#2563eb';
    const lls = points.map((p) => [p.lat, p.lon]);
    if (lls.length > 1) L.polyline(lls, { color, weight: 3, opacity: 0.75 }).addTo(layers.track);
    if ($('showPoints').checked) {
      points.forEach((p, i) => {
        L.circleMarker([p.lat, p.lon], { radius: 4, color, weight: 1, fillColor: i === 0 ? '#16a34a' : '#fff', fillOpacity: 1 })
          .bindPopup(`<div class="popup"><b>${fmt(p.ts)}</b>${p.accuracy ? `± ${Math.round(p.accuracy)} m<br>` : ''}`
            + `${p.is_own_report ? 'Reported by your own phone' : 'Reported by Find Hub network'}<br>`
            + `<span class="muted">${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}</span></div>`)
          .addTo(layers.points);
      });
    }
    if (!fitted) {
      const bounds = lls.length ? L.latLngBounds(lls) : null;
      const d = devices.find((x) => x.id === deviceId);
      if (bounds) map.fitBounds(bounds.pad(0.15), { maxZoom: 16 });
      else if (d?.last_lat != null) map.setView([d.last_lat, d.last_lon], 15);
      fitted = true;
    }
    const scrub = $('scrub');
    scrub.max = Math.max(0, points.length - 1); scrub.value = scrub.max;
    $('scrubLabel').innerHTML = '&nbsp;';
    renderStats();
  }

  function renderStats() {
    let dist = 0;
    for (let i = 1; i < points.length; i++) dist += km(points[i - 1], points[i]);
    const span = points.length ? points[points.length - 1].ts - points[0].ts : 0;
    const days = span / 86400;
    $('stats').innerHTML = [
      [points.length.toLocaleString(), 'locations'],
      [dist < 10 ? dist.toFixed(2) : Math.round(dist).toLocaleString(), 'km travelled'],
      [days >= 1 ? `${days.toFixed(days < 10 ? 1 : 0)} d` : `${Math.round(span / 3600)} h`, 'time span'],
    ].map(([v, k]) => `<div class="stat"><div class="v">${v}</div><div class="k">${k}</div></div>`).join('');
  }

  // ---------- playback ----------
  function showAt(i) {
    layers.play.clearLayers();
    const p = points[i];
    if (!p) return;
    const color = devices.find((d) => d.id === deviceId)?.color || '#2563eb';
    L.polyline(points.slice(0, i + 1).map((q) => [q.lat, q.lon]), { color: '#f59e0b', weight: 5, opacity: 0.9 }).addTo(layers.play);
    L.circleMarker([p.lat, p.lon], { radius: 10, color: '#fff', weight: 3, fillColor: color, fillOpacity: 1 }).addTo(layers.play);
    $('scrubLabel').textContent = `${i + 1} / ${points.length} · ${fmt(p.ts)}`;
    if (!map.getBounds().contains([p.lat, p.lon])) map.panTo([p.lat, p.lon]);
  }
  function stopPlay() { clearInterval(playTimer); playTimer = null; $('play').textContent = '▶'; }
  $('scrub').addEventListener('input', (e) => { stopPlay(); showAt(Number(e.target.value)); });
  $('play').addEventListener('click', () => {
    if (playTimer) return stopPlay();
    if (!points.length) return;
    const scrub = $('scrub');
    if (Number(scrub.value) >= points.length - 1) scrub.value = 0;
    $('play').textContent = '❚❚';
    const stepMs = Math.max(15, Math.min(300, 20000 / points.length));
    playTimer = setInterval(() => {
      const i = Number(scrub.value) + 1;
      if (i >= points.length) return stopPlay();
      scrub.value = i; showAt(i);
    }, stepMs);
  });

  // ---------- controls ----------
  function markRange() {
    document.querySelectorAll('#ranges button').forEach((b) => b.classList.toggle('on', b.dataset.h === range.h));
    $('custom').hidden = range.h !== 'custom';
  }
  $('ranges').addEventListener('click', (e) => {
    const h = e.target.dataset?.h; if (!h) return;
    range = { h }; markRange();
    if (h === 'custom') {
      const toLocal = (d) => new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
      $('to').value ||= toLocal(new Date()); $('from').value ||= toLocal(new Date(Date.now() - 7 * 86400000));
      return;
    }
    store.set('range', h); fitted = false; loadHistory().catch(alert);
  });
  $('applyCustom').addEventListener('click', () => {
    const s = new Date($('from').value), en = new Date($('to').value);
    if (isNaN(s) || isNaN(en) || s >= en) return alert('Pick a valid date range.');
    range = { h: 'custom', start: Math.floor(s / 1000), end: Math.floor(en / 1000) };
    fitted = false; loadHistory().catch(alert);
  });
  $('device').addEventListener('change', (e) => {
    deviceId = e.target.value; store.set('device', deviceId); fitted = false;
    renderLatest(); loadHistory().catch(alert);
  });
  $('showPoints').addEventListener('change', () => loadHistory().catch(alert));
  $('toggle').addEventListener('click', () => $('panel').classList.toggle('collapsed'));
  $('pollNow').addEventListener('click', async () => {
    try { await api('/api/poll-now', { method: 'POST' }); $('pollStatus').textContent = 'Checking Google for new locations…'; setTimeout(refresh, 15000); }
    catch (e) { alert(e.message); }
  });
  const download = (f) => {
    if (!deviceId) return;
    const [start, end] = currentRange();
    location.href = `/api/history/${encodeURIComponent(deviceId)}?start=${start}&end=${end}&format=${f}`;
  };
  $('csv').addEventListener('click', () => download('csv'));
  $('gpx').addEventListener('click', () => download('gpx'));
  $('rename').addEventListener('click', async () => {
    const d = devices.find((x) => x.id === deviceId); if (!d) return;
    const name = prompt('New name for this tag', d.name); if (!name) return;
    await api(`/api/devices/${encodeURIComponent(d.id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
    refresh();
  });
  if (window.TagTrackerApp) {
    $('server').hidden = false;
    $('server').addEventListener('click', () => window.TagTrackerApp.changeServer());
  }
  $('logout').addEventListener('click', async () => { await api('/api/logout', { method: 'POST' }); location.href = '/login'; });

  async function refresh() {
    try { await loadDevices(); if (!playTimer) await loadHistory(); } catch (e) { console.error(e); }
  }
  setInterval(() => { if ($('autoRefresh').checked && !document.hidden) refresh(); }, 60000);

  if (range.h === 'custom') range.h = '168';
  markRange();
  refresh();
})();
