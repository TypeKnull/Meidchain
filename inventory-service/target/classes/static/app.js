// MediChain web UI - Clean Working Prototype Script
// Connects to Inventory Service (port 8081) and Forecast Service (port 8082)

const state = { 
    district: null, 
    last: null, 
    chart: null, 
    runwayChart: null, 
    currentRunwayReport: null, 
    districtRunways: [],
    leafletMap: null,
    mapLayers: { markers: null, transfers: null, route: null },
    networkData: null,
    routePlan: null
};

// ---------------------------------------------------------------- Helpers
async function api(method, url, body) {
    const res = await fetch(url, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || data.message || ('HTTP ' + res.status));
    return data;
}

const fmt = n => (n === null || n === undefined) ? '-' : Number(n).toLocaleString('en-IN');
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const badge = s => `<span class="badge ${esc(String(s).replace(/ /g, '_'))}">${esc(s)}</span>`;

/* Toast Notification Engine */
function showToast(title, message, type = 'info') {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.className = 'toast-container';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    const icon = type === 'success' ? '✅' : type === 'warning' ? '⚠️' : type === 'error' ? '❌' : 'ℹ️';
    toast.innerHTML = `
        <div class="toast-icon">${icon}</div>
        <div class="toast-body">
            <div class="toast-title">${esc(title)}</div>
            <div class="toast-message">${esc(message)}</div>
        </div>
        <button class="toast-close" onclick="this.parentElement.remove()">&times;</button>
    `;
    container.appendChild(toast);
    setTimeout(() => {
        toast.classList.add('toast-exit');
        setTimeout(() => toast.remove(), 350);
    }, 4500);
}

/* Draws a data table */
function renderTable(id, columns, rows, emptyText) {
    const el = document.getElementById(id);
    if (!el) return;
    const head = '<thead><tr>' + columns.map(c => `<th class="${c.num ? 'num' : ''}">${c.title}</th>`).join('') + '</tr></thead>';
    const body = rows.length === 0
        ? `<tbody><tr class="empty"><td colspan="${columns.length}">${emptyText || 'Nothing to show'}</td></tr></tbody>`
        : '<tbody>' + rows.map((r, i) => '<tr>' + columns.map(c => `<td class="${c.num ? 'num' : ''}">${c.value(r, i)}</td>`).join('') + '</tr>').join('') + '</tbody>';
    el.innerHTML = head + body;
}

//  Tabs & Navigation
function navigateTab(tabName) {
    if (!tabName) return;
    if (typeof window.showTab === 'function' && document.getElementById('tab-' + tabName)) {
        window.showTab(tabName);
    } else {
        window.location.href = '/?tab=' + encodeURIComponent(tabName);
    }
}
window.navigateTab = navigateTab;

document.querySelectorAll('nav button.nav-tab').forEach(b => {
    b.addEventListener('click', (e) => {
        e.preventDefault();
        if (b.dataset.tab) navigateTab(b.dataset.tab);
    });
});

function showTab(name) {
    if (!name) return;
    document.querySelectorAll('nav .nav-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
    const target = document.getElementById('tab-' + name);
    if (target) {
        target.classList.remove('hidden');
        target.style.animation = 'none';
        void target.offsetHeight; // trigger reflow to replay reveal animation
        target.style.animation = '';
    }
    
    if (name === 'dashboard') loadDashboard();
    if (name === 'runway') loadRunwayData();
    if (name === 'map') {
        loadMapTab();
        setTimeout(() => { 
            if (state.leafletMap) {
                state.leafletMap.invalidateSize();
                resetMapView();
            }
        }, 200);
        setTimeout(() => {
            if (state.leafletMap) {
                state.leafletMap.invalidateSize();
            }
        }, 500);
    }
    if (name === 'data') { loadData(); loadBatches(); }
    if (name === 'transfers') { loadTransfers(); loadProcurementOrders(); }
}
window.showTab = showTab;

window.onDistrictChange = function(newDistrict) {
    if (!newDistrict) return;
    state.district = newDistrict;
    const activeTab = document.querySelector('nav .nav-tab.active')?.dataset.tab || 'dashboard';
    if (activeTab === 'dashboard') loadDashboard();
    else if (activeTab === 'runway') loadRunwayData();
    else if (activeTab === 'map') loadMapTab();
    else if (activeTab === 'data') { loadData(); loadBatches(); }
    else if (activeTab === 'transfers') { loadTransfers(); loadProcurementOrders(); }
};

async function init() {
    try {
        const sel = document.getElementById('district');
        const urlParams = new URLSearchParams(window.location.search);
        const urlDistrict = urlParams.get('district');

        let districts = [];
        try {
            districts = await api('GET', '/api/districts');
            if (sel && districts.length > 0) {
                const currentVal = urlDistrict || sel.value || districts[0];
                sel.innerHTML = districts.map(d => `<option value="${esc(d)}" ${d === currentVal ? 'selected' : ''}>${esc(d)}</option>`).join('');
                state.district = currentVal;
            }
        } catch (e) {
            console.warn('Could not fetch districts:', e);
        }

        if (!state.district && sel) {
            state.district = sel.value || 'Vellore';
        }

        if (sel) {
            sel.addEventListener('change', () => {
                if (window.onDistrictChange) window.onDistrictChange(sel.value);
            });
        }

        // Check if a specific tab was requested via ?tab= or #
        const requestedTab = urlParams.get('tab') || window.location.hash.replace('#', '');
        if (requestedTab && ['dashboard', 'runway', 'map', 'ask', 'transfers', 'data'].includes(requestedTab)) {
            showTab(requestedTab);
        } else {
            showTab('dashboard');
        }
    } catch (e) {
        const status = document.getElementById('dashInfo');
        if (status) status.textContent = 'Error connecting to backend: ' + e.message;
    }
}

// 1. Dashboard Tab
async function loadDashboard() {
    const info = document.getElementById('dashInfo');
    if (info) info.textContent = 'Fetching live district metrics...';
    try {
        const d = await api('GET', '/api/dashboard?district=' + encodeURIComponent(state.district));
        const h = d.history;
        if (info) {
            info.textContent = `${d.district} District • 90-Day History Analysis • Live as of ${d.asOf}`;
        }
        
        const cardsEl = document.getElementById('cards');
        if (cardsEl) {
            const cardItems = [
                { val: d.riskLevels.CRITICAL + d.riskLevels.HIGH, lbl: 'Critical Stockouts', ic: '🚨', v: 'danger', sub: 'Facilities at risk within 14 days' },
                { val: h.fillRatePct + '%', lbl: 'Fulfillment Rate', ic: '📈', v: 'success', sub: 'Prescriptions successfully issued' },
                { val: h.stockOutRatePct + '%', lbl: 'Stock-out Rate', ic: '⏱️', v: 'primary', sub: 'Days with unmet demand' },
                { val: d.transfersRecommended, lbl: 'Recommended Transfers', ic: '🔄', v: 'primary', sub: 'Surplus rebalancing available' },
                { val: fmt(d.expiringUnits), lbl: 'Expiring Units', ic: '⏳', v: 'warning', sub: 'Units at risk of expiring on shelf' },
                { val: '₹' + fmt(h.wastedValueInr), lbl: 'Stock Wastage', ic: '🗑️', v: 'danger', sub: `${fmt(h.wastedUnits)} units expired` },
                { val: h.transfersCompleted, lbl: 'Transfers Completed', ic: '✅', v: 'success', sub: `${fmt(h.unitsTransferred)} units reallocated` },
                { val: d.ordersSuggested, lbl: 'Purchase Reorders', ic: '🛒', v: 'primary', sub: 'Orders recommended this cycle' }
            ];
            cardsEl.innerHTML = cardItems.map((c, idx) => `
                <div class="kpi-card ${c.v ? 'kpi-' + c.v : ''}" style="animation: pageReveal 0.35s cubic-bezier(0.16, 1, 0.3, 1) both; animation-delay: ${idx * 40}ms;">
                    <div class="kpi-header">
                        <span class="kpi-label">${c.lbl}</span>
                        <span class="kpi-icon">${c.ic}</span>
                    </div>
                    <div class="kpi-value">${c.val}</div>
                    ${c.sub ? `<div class="kpi-detail">${c.sub}</div>` : ''}
                </div>
            `).join('');
        }

        renderTable('dashRisk', [
            { title: 'Facility', value: x => `<b>${esc(x.facilityName)}</b>` },
            { title: 'Medicine', value: x => esc(x.medicineName) },
            { title: 'Usable Stock', num: true, value: x => fmt(x.usableStock) + ' ' + esc(x.unit) + 's' },
            { title: 'Days Left', num: true, value: x => `<span style="font-weight:700; color:${x.daysToStockout <= 7 ? '#ef4444' : '#d97706'}">${x.daysToStockout} days</span>` },
            { title: 'Risk Level', value: x => badge(x.riskLevel) },
            { title: 'Action', value: x => `
                <button class="small" onclick="quickPlan('${esc(x.medicineName)}')">Resolve</button>
                <button class="small" style="margin-left:4px; background:#4f46e5; border-color:#4338ca;" onclick="quickInspectRunway(${x.facilityId}, ${x.medicineId})">Runway</button>
            ` }
        ], d.runningOutIn14Days, 'All facilities currently have adequate safety buffer.');

        renderTable('dashMed', [
            { title: 'Medicine', value: r => esc(r.medicine) },
            { title: 'Fill Rate', num: true, value: r => `<b>${r.fillRatePct}%</b>` },
            { title: 'Stock-out Rate', num: true, value: r => `${r.stockOutRatePct}%` }
        ], h.byMedicine);

        loadDiseaseSurveillance(state.district);

    } catch (e) {
        if (info) info.textContent = 'Error loading dashboard: ' + e.message;
    }
}

async function loadDiseaseSurveillance(district) {
    try {
        const data = await api('GET', '/api/disease-trends?district=' + encodeURIComponent(district));
        const diseases = data.diseases || {};
        const rows = Object.entries(diseases).map(([name, points]) => {
            const sorted = [...points].sort((a, b) => a.weekStart.localeCompare(b.weekStart));
            const latest = sorted[sorted.length - 1]?.cases ?? 0;
            const prev = sorted[sorted.length - 2]?.cases ?? latest;
            const pctChange = prev === 0 ? 0 : Math.round(100.0 * (latest - prev) / prev);
            const trendIcon = pctChange > 5 ? `🔺 +${pctChange}% (Surging)` : pctChange < -5 ? `🔻 ${pctChange}% (Falling)` : `➡️ Stable`;
            const driverMap = {
                'FEVER': 'Drives demand for Paracetamol & Ibuprofen',
                'DIARRHOEA': 'Drives demand for ORS & Zinc Sulphate',
                'ARI': 'Drives demand for Amoxicillin & Azithromycin'
            };
            return {
                name,
                latest,
                prev,
                trend: trendIcon,
                impact: driverMap[name] || 'General Morbidity Driver',
                recentWeeks: sorted.slice(-4).map(p => `${p.weekStart.substring(5)}: ${p.cases}`).join(' | ')
            };
        });

        renderTable('dashDisease', [
            { title: 'Epidemiological Syndrome', value: r => `<b>${esc(r.name)}</b>` },
            { title: 'Latest Weekly Cases', num: true, value: r => `<b>${fmt(r.latest)}</b> cases` },
            { title: 'Weekly Trend', value: r => r.trend },
            { title: 'Medicine Supply Impact', value: r => `<span class="badge LOW">${esc(r.impact)}</span>` },
            { title: 'Recent Weekly Trajectory', value: r => `<code style="font-size:11px;">${esc(r.recentWeeks)}</code>` }
        ], rows, 'No disease caseload records available for this district.');
    } catch (e) {
        console.error('Could not load disease surveillance:', e);
    }
}

function quickPlan(medName) {
    document.getElementById('question').value = `Which facilities in the district are at risk of running out of ${medName} in the next 14 days, and where can stock come from?`;
    showTab('ask');
    ask();
}

// ---------------------------------------------------------------- 2. Ask & Analyse Tab
document.querySelectorAll('.example-chips .chip').forEach(b => b.addEventListener('click', () => {
    document.getElementById('question').value = b.dataset.q;
    ask();
}));

document.getElementById('askBtn').addEventListener('click', ask);
document.getElementById('onlyRisk').addEventListener('change', () => state.last && renderRisks(state.last));

async function ask() {
    const status = document.getElementById('askStatus');
    const btn = document.getElementById('askBtn');
    status.textContent = 'Running AI simulation & transfer optimization...';
    btn.disabled = true;
    try {
        const answer = await api('POST', '/api/query', {
            question: document.getElementById('question').value,
            district: state.district
        });
        const r = answer.result;
        state.last = r;
        state.district = r.district;
        document.getElementById('district').value = r.district;
        document.getElementById('interpretation').textContent = 'AI Query Interpretation: ' + answer.understoodAs;
        renderResult(r);
        status.textContent = 'Completed (as of ' + r.asOf + ')';
        showToast('AI Simulation Complete', `Analyzed ${r.risks?.length || 0} facility risk points & calculated ${r.transfers?.length || 0} transfers.`, 'info');
    } catch (e) {
        status.textContent = 'Error: ' + e.message;
        showToast('Simulation Failed', e.message, 'error');
    } finally {
        btn.disabled = false;
    }
}

function renderResult(r) {
    document.getElementById('results').classList.remove('hidden');
    document.getElementById('summary').innerHTML = r.summary.map(s => `<li>${esc(s)}</li>`).join('');
    document.getElementById('method').textContent = r.method;
    renderRisks(r);

    renderTable('transferTable', [
        { title: 'Medicine', value: t => `<b>${esc(t.medicineName)}</b>` },
        { title: 'Source (Surplus)', value: t => esc(t.fromFacility) },
        { title: 'Destination (Deficit)', value: t => `<b>${esc(t.toFacility)}</b>` },
        { title: 'Transfer Qty', num: true, value: t => `<b>${fmt(t.quantity)}</b> ${esc(t.unit)}s` },
        { title: 'Distance', num: true, value: t => t.distanceKm + ' km' },
        { title: 'Stock Horizon', value: t => `${t.daysOfStockBefore}d &rarr; <b>${t.daysOfStockAfter ?? '180+'}d</b>` },
        { title: 'Urgency', value: t => badge(t.urgency) },
        { title: 'Transfer Rationale', value: t => `<div class="why">${esc(t.rationale)}</div>` },
        { title: 'Action', value: (t, i) => `<button class="small" onclick="approve(${i}, this)">Approve Transfer</button>` }
    ], r.transfers, 'No transfers needed; all facilities are safely balanced.');

    renderTable('procTable', [
        { title: 'Facility', value: o => esc(o.facilityName) },
        { title: 'Medicine', value: o => esc(o.medicineName) },
        { title: 'Stock Post-Transfer', num: true, value: o => fmt(o.stockAfterTransfers) },
        { title: 'Forecast/Day', num: true, value: o => fmt(o.forecastPerDay) },
        { title: 'Lead Time', num: true, value: o => o.leadTimeDays + ' days' },
        { title: 'Reorder Point', num: true, value: o => fmt(o.reorderPoint) },
        { title: 'Order Quantity', num: true, value: o => `<b>${fmt(o.orderQuantity)}</b>` },
        { title: 'Order By', value: o => o.orderBy },
        { title: 'Urgency', value: o => badge(o.urgency) },
        { title: 'Estimated Cost', num: true, value: o => '₹' + fmt(o.costInr) },
        { title: 'Action', value: (o, i) => `<button class="small" onclick="approvePO(${i}, this)">Approve &amp; Order</button>` }
    ], r.orders, 'No procurement orders required at this time.');

    renderTable('expiryTable', [
        { title: 'Facility', value: e => esc(e.facilityName) },
        { title: 'Medicine', value: e => esc(e.medicineName) },
        { title: 'Batch #', value: e => esc(e.batchNo) },
        { title: 'Expiry Date', value: e => `${e.expiryDate} (${e.daysToExpiry}d)` },
        { title: 'Batch Quantity', num: true, value: e => fmt(e.batchQuantity) },
        { title: 'Unused Units at Risk', num: true, value: e => `<b style="color:#ef4444">${fmt(e.unitsAtRisk)}</b>` },
        { title: 'Value', num: true, value: e => '₹' + fmt(e.valueInr) },
        { title: 'Mitigation Plan', value: e => `<div class="why">${esc(e.action)}</div>` }
    ], r.expiry, 'No batches projected to expire before use.');
}

function renderRisks(r) {
    const onlyRisk = document.getElementById('onlyRisk').checked;
    const rows = onlyRisk ? r.risks.filter(x => x.riskLevel !== 'LOW') : r.risks;
    renderTable('riskTable', [
        { title: 'Facility', value: x => esc(x.facilityName) },
        { title: 'Medicine', value: x => esc(x.medicineName) },
        { title: 'Usable Stock', num: true, value: x => fmt(x.usableStock) },
        { title: 'Past Usage (14d)', num: true, value: x => fmt(x.usedPerDayLast14) + '/d' },
        { title: `Forecast (Next ${r.horizonDays}d)`, num: true, value: x => fmt(x.forecastPerDay) + '/d' },
        { title: 'Days to Depletion', num: true, value: x => `<b>${x.daysToStockout ?? '180+'}d</b>` },
        { title: 'Stockout Date', value: x => x.stockoutDate ?? 'Safe' },
        { title: `Shortage Risk`, num: true, value: x => x.stockoutChancePct + '%' },
        { title: 'Status', value: x => badge(x.riskLevel) },
        { title: 'Analysis', value: x => `<button class="small" onclick="why(${r.risks.indexOf(x)})">Inspect</button>` }
    ], rows, 'All facilities hold sufficient safety stock.');
}

async function approve(i, btn) {
    const t = state.last.transfers[i];
    btn.disabled = true;
    try {
        const saved = await api('POST', '/api/transfers', {
            fromFacilityId: t.fromFacilityId, toFacilityId: t.toFacilityId, medicineId: t.medicineId,
            quantity: t.quantity, distanceKm: t.distanceKm, rationale: t.rationale
        });
        btn.outerHTML = `<span class="badge APPROVED">Order #${saved.id} Created</span>`;
        showToast('Transfer Approved', `Transfer Order #${saved.id} (${fmt(t.quantity)} units) queued.`, 'success');
    } catch (e) {
        btn.disabled = false;
        alert('Could not approve transfer: ' + e.message);
    }
}

async function approvePO(i, btn) {
    const o = state.last.orders[i];
    btn.disabled = true;
    try {
        const po = await api('POST', '/api/purchase-orders', {
            facilityId: o.facilityId,
            medicineId: o.medicineId,
            quantity: o.orderQuantity,
            leadTimeDays: o.leadTimeDays,
            expectedDeliveryDate: o.orderBy,
            unitPrice: (o.orderQuantity > 0 ? o.costInr / o.orderQuantity : 1.0),
            totalCost: o.costInr,
            urgency: o.urgency
        });
        btn.outerHTML = `<span class="badge ORDERED">PO #${po.id} Placed</span>`;
        showToast('Purchase Order Placed', `PO #${po.id} created for ${fmt(o.orderQuantity)} units.`, 'success');
    } catch (e) {
        btn.disabled = false;
        alert('Could not create purchase order: ' + e.message);
    }
}

// "Why?" Modal
async function why(i) {
    const x = state.last.risks[i];
    document.getElementById('modalTitle').textContent = `${x.medicineName} Stockout Forecast — ${x.facilityName}`;
    document.getElementById('modalFactors').innerHTML = x.keyFactors.map(f => `<li>${esc(f)}</li>`).join('');
    document.getElementById('modalModel').textContent = 'AI Predictive Model: ' + x.model;
    document.getElementById('modal').classList.remove('hidden');
    try {
        const past = await api('GET', `/api/consumption?facilityId=${x.facilityId}&medicineId=${x.medicineId}&days=60`);
        drawChart(past, x.forecastNext30, state.last.asOf);
    } catch (e) {
        document.getElementById('modalModel').textContent += ' | Chart error: ' + e.message;
    }
}

function drawChart(past, next30, asOf) {
    if (typeof Chart === 'undefined') return;
    const start = new Date(asOf);
    const futureDates = next30.map((_, k) => new Date(start.getTime() + k * 86400000).toISOString().substring(0, 10));
    const blank = n => new Array(n).fill(null);
    if (state.chart) state.chart.destroy();
    state.chart = new Chart(document.getElementById('chart'), {
        type: 'line',
        data: {
            labels: [...past.map(d => d.date), ...futureDates],
            datasets: [
                { label: 'Past Demand', data: [...past.map(d => d.demanded), ...blank(next30.length)], borderColor: '#0284c7', pointRadius: 0, borderWidth: 1.5 },
                { label: 'Past Issued', data: [...past.map(d => d.issued), ...blank(next30.length)], borderColor: '#94a3b8', borderDash: [4, 3], pointRadius: 0, borderWidth: 1 },
                { label: 'AI Forecast', data: [...blank(past.length), ...next30], borderColor: '#2563eb', pointRadius: 0, borderWidth: 2 }
            ]
        },
        options: { 
            responsive: true,
            interaction: { mode: 'index', intersect: false }, 
            scales: { x: { ticks: { maxTicksLimit: 10 } } } 
        }
    });
}

document.getElementById('modalClose').addEventListener('click', () => document.getElementById('modal').classList.add('hidden'));
document.getElementById('modal').addEventListener('click', e => { if (e.target.id === 'modal') e.target.classList.add('hidden'); });

// ---------------------------------------------------------------- 3. Master Data & Stock Tab
async function loadData() {
    try {
        const [stock, meds, facs] = await Promise.all([
            api('GET', '/api/stock?district=' + encodeURIComponent(state.district)),
            api('GET', '/api/medicines'),
            api('GET', '/api/facilities?district=' + encodeURIComponent(state.district))
        ]);
        document.querySelectorAll('.facSelect').forEach(s => s.innerHTML = facs.map(f => `<option value="${f.id}">${esc(f.name)}</option>`).join(''));
        document.querySelectorAll('.medSelect').forEach(s => s.innerHTML = meds.map(m => `<option value="${m.id}">${esc(m.name)} (${esc(m.strength)})</option>`).join(''));

        renderTable('stockTable', [
            { title: 'Facility', value: s => `<b>${esc(s.facility)}</b>` },
            { title: 'Medicine', value: s => esc(s.medicine) },
            { title: 'Usable Quantity', num: true, value: s => `<b>${fmt(s.usableQuantity)}</b>` },
            { title: 'Batches', num: true, value: s => s.batches },
            { title: 'Nearest Expiry', value: s => s.nearestExpiry ?? '-' },
            { title: 'Expired (Waste)', num: true, value: s => s.expiredQuantity ? `<span style="color:#ef4444">${fmt(s.expiredQuantity)}</span>` : '-' }
        ], stock);

        renderTable('medTable', [
            { title: 'Code', value: m => `<code>${esc(m.code)}</code>` },
            { title: 'Medicine Name', value: m => `<b>${esc(m.name)}</b>` },
            { title: 'Strength', value: m => esc(m.strength) },
            { title: 'Unit', value: m => esc(m.unit) },
            { title: 'Epidemiological Driver', value: m => `<span class="badge LOW">${esc(m.diseaseTag)}</span>` },
            { title: 'Lead Time', num: true, value: m => m.leadTimeDays + ' d' },
            { title: 'Unit Price', num: true, value: m => '₹' + m.unitPrice }
        ], meds);

        renderTable('facTable', [
            { title: 'Code', value: f => `<code>${esc(f.code)}</code>` },
            { title: 'Facility Name', value: f => `<b>${esc(f.name)}</b>` },
            { title: 'Facility Type', value: f => `<span class="badge APPROVED">${esc(f.type)}</span>` },
            { title: 'District', value: f => esc(f.district) },
            { title: 'Coordinates', value: f => `${f.latitude}, ${f.longitude}` },
            { title: 'Catchment Pop.', num: true, value: f => fmt(f.population) }
        ], facs);
    } catch (e) {
        alert('Could not load inventory data: ' + e.message);
    }
}

function formToObject(form) {
    const o = {};
    new FormData(form).forEach((v, k) => { if (v !== '') o[k] = v; });
    return o;
}

document.getElementById('batchForm').addEventListener('submit', async ev => {
    ev.preventDefault();
    const msg = ev.target.querySelector('.formMsg');
    try {
        const o = formToObject(ev.target);
        o.quantity = Number(o.quantity);
        const b = await api('POST', '/api/stock/batches', o);
        msg.textContent = `Batch #${b.batchNo} saved successfully.`;
        ev.target.reset();
        loadData();
    } catch (e) { msg.textContent = 'Error: ' + e.message; }
});

document.getElementById('consForm').addEventListener('submit', async ev => {
    ev.preventDefault();
    const msg = ev.target.querySelector('.formMsg');
    try {
        const o = formToObject(ev.target);
        o.issued = Number(o.issued);
        if (o.demanded !== undefined) o.demanded = Number(o.demanded);
        const c = await api('POST', '/api/consumption', o);
        msg.textContent = `Recorded: ${c.issued} units dispensed on ${c.date}.`;
        ev.target.reset();
        loadData();
    } catch (e) { msg.textContent = 'Error: ' + e.message; }
});

// ---------------------------------------------------------------- 4. Transfers Tab
async function loadTransfers() {
    try {
        const list = await api('GET', '/api/transfers');
        renderTable('ordersTable', [
            { title: 'Order #', value: t => `<b>#${t.id}</b>` },
            { title: 'Medicine', value: t => `<b>${esc(t.medicine)}</b>` },
            { title: 'Source Facility', value: t => esc(t.fromFacility) },
            { title: 'Destination Facility', value: t => esc(t.toFacility) },
            { title: 'Quantity', num: true, value: t => `<b>${fmt(t.quantity)}</b>` },
            { title: 'Distance', num: true, value: t => t.distanceKm + ' km' },
            { title: 'Status', value: t => badge(t.status) },
            { title: 'Created', value: t => esc(t.createdAt) },
            { title: 'Rationale', value: t => `<div class="why">${esc(t.rationale)}</div>` },
            { title: 'Action', value: t => t.status === 'APPROVED'
                    ? `<button class="small" onclick="transferAction(${t.id}, 'complete')">✓ Complete</button>
                       <button class="small" style="margin-left:4px;" onclick="transferAction(${t.id}, 'reject')">✗ Reject</button>` : '' }
        ], list, 'No transfers generated yet. Run a forecast in the AI assistant to approve transfers.');
    } catch (e) {
        alert('Could not load transfer orders: ' + e.message);
    }
}

async function transferAction(id, action) {
    try {
        await api('POST', `/api/transfers/${id}/${action}`);
        showToast(action === 'complete' ? 'Transfer Completed' : 'Transfer Rejected',
                  action === 'complete' ? `Stock quantities updated in facility ledgers.` : `Transfer Order #${id} was rejected.`,
                  action === 'complete' ? 'success' : 'warning');
        loadTransfers();
    } catch (e) { alert(e.message); }
}

async function loadProcurementOrders() {
    try {
        const list = await api('GET', '/api/purchase-orders');
        renderTable('poTable', [
            { title: 'PO #', value: po => `<b>#PO-${po.id}</b>` },
            { title: 'Medicine', value: po => `<b>${esc(po.medicine)}</b>` },
            { title: 'Destination Facility', value: po => esc(po.facility) },
            { title: 'Order Qty', num: true, value: po => `<b>${fmt(po.quantity)}</b>` },
            { title: 'Expected Delivery', value: po => `${po.expectedDeliveryDate} (${po.leadTimeDays}d lead)` },
            { title: 'Total Cost', num: true, value: po => '₹' + fmt(po.totalCost) },
            { title: 'Urgency', value: po => badge(po.urgency) },
            { title: 'Status', value: po => badge(po.status) },
            { title: 'Created', value: po => esc(po.createdAt) },
            { title: 'Action', value: po => po.status === 'ORDERED'
                ? `<button class="small" onclick="receivePO(${po.id})">✓ Receive Stock</button>
                   <button class="small" style="margin-left:4px;" onclick="cancelPO(${po.id})">✗ Cancel</button>`
                : (po.status === 'RECEIVED' ? `<span class="muted" style="font-size:11px;">Checked in ${esc(po.receivedAt)}</span>` : '') }
        ], list, 'No purchase orders placed yet. Run a forecast in the AI assistant to approve suggested procurement orders.');
    } catch (e) {
        console.error('Could not load procurement orders:', e);
    }
}

async function receivePO(id) {
    const batchNo = prompt('Enter Batch Number for incoming stock (or keep default):', 'BATCH-' + Math.floor(1000 + Math.random() * 9000));
    if (batchNo === null) return;
    const expiry = prompt('Enter Expiry Date (YYYY-MM-DD):', new Date(Date.now() + 730 * 86400000).toISOString().substring(0, 10));
    if (!expiry) return;

    try {
        await api('POST', `/api/purchase-orders/${id}/receive`, {
            batchNo: batchNo.trim(),
            expiryDate: expiry.trim()
        });
        showToast('Stock Received', `Batch ${batchNo} checked into facility inventory.`, 'success');
        loadProcurementOrders();
        loadData();
        loadBatches();
    } catch (e) {
        alert('Failed to receive order: ' + e.message);
    }
}

async function cancelPO(id) {
    if (!confirm('Are you sure you want to cancel Purchase Order #' + id + '?')) return;
    try {
        await api('POST', `/api/purchase-orders/${id}/cancel`);
        loadProcurementOrders();
    } catch (e) {
        alert('Failed to cancel order: ' + e.message);
    }
}

async function loadBatches() {
    try {
        const districtParam = state.district ? '?district=' + encodeURIComponent(state.district) : '';
        const list = await api('GET', '/api/stock/batches' + districtParam);
        renderTable('batchLedgerTable', [
            { title: 'Batch #', value: b => `<code>${esc(b.batchNo)}</code>` },
            { title: 'Facility', value: b => `<b>${esc(b.facility)}</b>` },
            { title: 'Medicine', value: b => esc(b.medicine) },
            { title: 'Current Stock', num: true, value: b => `<b>${fmt(b.quantity)}</b>` },
            { title: 'Expiry Date', value: b => b.expiryDate },
            { title: 'Shelf-Life Remaining', num: true, value: b => b.daysToExpiry < 0 ? `<span style="color:#ef4444;font-weight:700;">Expired (${Math.abs(b.daysToExpiry)}d ago)</span>` : `${b.daysToExpiry} days` },
            { title: 'Condition', value: b => b.isExpired
                ? `<span class="badge CRITICAL">EXPIRED</span>`
                : (b.daysToExpiry <= 45 ? `<span class="badge HIGH">NEAR EXPIRY</span>` : `<span class="badge APPROVED">ACTIVE</span>`) }
        ], list, 'No batches currently stored for this district.');
    } catch (e) {
        console.error('Could not load batches:', e);
    }
}

// Master Data Forms
document.getElementById('newFacForm')?.addEventListener('submit', async ev => {
    ev.preventDefault();
    const msg = ev.target.querySelector('.formMsg');
    try {
        const o = formToObject(ev.target);
        o.latitude = Number(o.latitude);
        o.longitude = Number(o.longitude);
        o.population = Number(o.population);
        const f = await api('POST', '/api/facilities', o);
        msg.textContent = `Facility ${f.name} registered successfully.`;
        ev.target.reset();
        loadData();
    } catch (e) { msg.textContent = 'Error: ' + e.message; }
});

document.getElementById('newMedForm')?.addEventListener('submit', async ev => {
    ev.preventDefault();
    const msg = ev.target.querySelector('.formMsg');
    try {
        const o = formToObject(ev.target);
        o.leadTimeDays = Number(o.leadTimeDays);
        o.unitPrice = Number(o.unitPrice);
        const m = await api('POST', '/api/medicines', o);
        msg.textContent = `Medicine ${m.name} added to master list.`;
        ev.target.reset();
        loadData();
    } catch (e) { msg.textContent = 'Error: ' + e.message; }
});

// ---------------------------------------------------------------- Autonomous Auto-Pilot
async function triggerAutoPilot() {
    const btn = document.getElementById('autoPilotBtn');
    if (btn) { btn.disabled = true; btn.textContent = '⏳ Running Auto-Pilot...'; }
    try {
        const districtParam = state.district ? '?district=' + encodeURIComponent(state.district) : '';
        const summary = await api('POST', '/api/autopilot/run' + districtParam);
        showToast('Auto-Pilot Rebalance Finished', 
                  `Executed ${summary.transfersCompleted} transfers • ${summary.ordersPlaced} POs placed • Rescued ${summary.expiringUnitsRescued} units`, 
                  'success');

        let msg = `🤖 Auto-Pilot Complete for ${summary.district || 'District'}!\n\n`;
        msg += `• Transfers Auto-Executed: ${summary.transfersCompleted}\n`;
        msg += `• Expiring Units Rescued: ${summary.expiringUnitsRescued}\n`;
        msg += `• Purchase Orders Placed: ${summary.ordersPlaced}\n`;
        msg += `• Stockouts Prevented: ${summary.stockoutsPrevented}\n`;
        if (summary.actionLogs && summary.actionLogs.length > 0) {
            msg += `\nActions Taken:\n- ` + summary.actionLogs.join('\n- ');
        } else {
            msg += `\nAll facilities are currently balanced; no emergency transfers or orders needed.`;
        }
        alert(msg);
        
        // Refresh active views
        loadDashboard();
        loadTransfers();
        loadProcurementOrders();
        loadBatches();
    } catch (e) {
        alert('Auto-Pilot execution error: ' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = '⚡ Run Autonomous Rebalance'; }
    }
}

async function showAutoPilotHistory() {
    try {
        const list = await api('GET', '/api/autopilot/history');
        renderTable('autoPilotTable', [
            { title: 'Time', value: a => esc(a.timestamp ? a.timestamp.replace('T', ' ').substring(0, 19) : '') },
            { title: 'Action Type', value: a => badge(a.type === 'AUTO_TRANSFER' ? 'AUTO_APPROVED' : 'AUTO_ORDERED') },
            { title: 'Facility', value: a => `<b>${esc(a.facility)}</b>` },
            { title: 'Medicine', value: a => esc(a.medicine) },
            { title: 'Quantity', num: true, value: a => fmt(a.quantity) },
            { title: 'Autonomous Rationale', value: a => `<div class="why">${esc(a.rationale)}</div>` }
        ], [...list].reverse(), 'No automated actions recorded yet. Click "Run Autonomous Rebalance" to execute auto-protection.');
        document.getElementById('autoPilotModal').classList.remove('hidden');
    } catch (e) {
        alert('Could not load auto-pilot history: ' + e.message);
    }
}

// ---------------------------------------------------------------- 5. AI Stock Runway & Depletion Predictor
let runwaySelectsLoaded = false;

async function loadRunwayData() {
    const subtitle = document.getElementById('runwaySubtitle');
    if (subtitle) subtitle.textContent = `Analyzing stock depletion velocities and seasonal disease surges for ${state.district}...`;

    try {
        // 1. Populate dropdowns if not loaded yet
        if (!runwaySelectsLoaded) {
            const [facs, meds] = await Promise.all([
                api('GET', '/api/facilities?district=' + encodeURIComponent(state.district)),
                api('GET', '/api/medicines')
            ]);
            const facSel = document.getElementById('runwayFacSelect');
            const medSel = document.getElementById('runwayMedSelect');
            if (facSel) {
                facSel.innerHTML = facs.map(f => `<option value="${f.id}">${esc(f.name)} (${esc(f.type)})</option>`).join('');
            }
            if (medSel) {
                medSel.innerHTML = meds.map(m => `<option value="${m.id}">${esc(m.name)} [${esc(m.code)}]</option>`).join('');
            }
            runwaySelectsLoaded = true;
            setupRunwaySliders();
        }

        // 2. Fetch district-wide runway summary
        const summary = await api('GET', '/api/analytics/stock-runway?district=' + encodeURIComponent(state.district));
        state.districtRunways = summary.items || [];

        if (subtitle) {
            subtitle.textContent = `${summary.district} District • ${summary.totalMonitored} Facilities & Medicines • ${summary.criticalDepletionCount} Critical Depletions (≤7d) • As of ${summary.asOf}`;
        }

        // 3. Render district summary table
        renderDistrictRunwayTable(state.districtRunways);

        // 4. Load deep-dive for selected or first critical item
        const facSel = document.getElementById('runwayFacSelect');
        const medSel = document.getElementById('runwayMedSelect');
        const targetFacId = facSel && facSel.value ? Number(facSel.value) : (state.districtRunways[0]?.facilityId || 1);
        const targetMedId = medSel && medSel.value ? Number(medSel.value) : (state.districtRunways[0]?.medicineId || 1);

        if (facSel && targetFacId) facSel.value = targetFacId;
        if (medSel && targetMedId) medSel.value = targetMedId;

        await loadRunwayDeepDive(targetFacId, targetMedId);

    } catch (e) {
        console.error('Error loading runway data:', e);
        if (subtitle) subtitle.textContent = 'Error loading stock runway analytics: ' + e.message;
    }
}

function renderDistrictRunwayTable(items) {
    renderTable('districtRunwayTable', [
        { title: 'Facility', value: r => `<b>${esc(r.facilityName)}</b>` },
        { title: 'Medicine', value: r => `${esc(r.medicineName)} <code style="font-size:11px;">(${esc(r.unit)})</code>` },
        { title: 'Usable Stock', num: true, value: r => `<b>${fmt(r.usableStock)}</b>` },
        { title: 'Daily Demand', num: true, value: r => `<span>Base: ${r.baseDemandPerDay} &rarr; <b>${r.seasonalDemandPerDay}</b>/d</span>` },
        { title: 'Seasonal Factor', value: r => r.seasonalSurgePct > 0 
            ? `<span class="badge WARNING">+${r.seasonalSurgePct}% (${esc(r.diseaseTag)})</span>`
            : `<span class="badge LOW">1.0x (Baseline)</span>` },
        { title: 'Days to Depletion', num: true, value: r => r.daysRemaining === null 
            ? `<span style="color:#10b981;font-weight:700;">180+ days (Safe)</span>` 
            : `<span style="font-weight:700; color:${r.daysRemaining <= 7 ? '#ef4444' : r.daysRemaining <= 14 ? '#d97706' : '#2563eb'}">${r.daysRemaining} days (~${r.runwayWeeks}w)</span>` },
        { title: 'Depletion Date', value: r => r.depletionDate ? `<b>${r.depletionDate}</b>` : 'Safe (>180d)' },
        { title: 'Risk Level', value: r => badge(r.riskLevel) },
        { title: 'Action', value: r => `<button class="small" onclick="quickSelectRunway(${r.facilityId}, ${r.medicineId})">Inspect Deep-Dive</button>` }
    ], items, 'No inventory items currently tracked in this district.');
}

async function runSelectedRunway() {
    const facId = Number(document.getElementById('runwayFacSelect').value);
    const medId = Number(document.getElementById('runwayMedSelect').value);
    await loadRunwayDeepDive(facId, medId);
}

async function quickSelectRunway(facilityId, medicineId) {
    const facSel = document.getElementById('runwayFacSelect');
    const medSel = document.getElementById('runwayMedSelect');
    if (facSel) facSel.value = facilityId;
    if (medSel) medSel.value = medicineId;
    await loadRunwayDeepDive(facilityId, medicineId);
    document.getElementById('tab-runway')?.scrollIntoView({ behavior: 'smooth' });
}

function quickInspectRunway(facilityId, medicineId) {
    showTab('runway');
    setTimeout(() => quickSelectRunway(facilityId, medicineId), 150);
}

async function loadRunwayDeepDive(facilityId, medicineId) {
    try {
        const report = await api('GET', `/api/analytics/stock-runway?district=${encodeURIComponent(state.district)}&facilityId=${facilityId}&medicineId=${medicineId}`);
        if (!report) return;
        state.currentRunwayReport = report;

        document.getElementById('runwayDetailContainer')?.classList.remove('hidden');

        // 1. Render KPI Cards
        renderRunwayCards(report);

        // 2. Render Trajectory Chart
        renderRunwayChart(report.trajectory, report.asOf, null);

        // 3. Render Historical Box
        renderHistoricalStats(report);

        // 4. Render Seasonality Box
        renderSeasonalityStats(report);

        // 5. Render Batch FEFO Table
        renderRunwayBatchTable(report.batchDetails, report.unit);

        // 6. Render AI Insights
        const insightsEl = document.getElementById('runwayAiInsights');
        if (insightsEl) {
            insightsEl.innerHTML = report.aiInsights.map(s => `<li>${esc(s)}</li>`).join('');
        }

        // 7. Reset What-If Sliders
        const simStock = document.getElementById('simStockSlider');
        const simSurge = document.getElementById('simSurgeSlider');
        if (simStock) simStock.value = 0;
        if (simSurge) simSurge.value = 1.0;
        document.getElementById('sliderStockVal').textContent = '+0 units';
        document.getElementById('sliderSurgeVal').textContent = '1.0x (Actual Forecast)';
        updateSimulationBanner(report, report);

    } catch (e) {
        alert('Failed to load runway report: ' + e.message);
    }
}

function renderRunwayCards(report) {
    const cardsEl = document.getElementById('runwayCards');
    if (!cardsEl) return;

    const runwayText = report.daysRemaining === null ? '180+ Days' : `${report.daysRemaining} Days`;
    const runwaySub = report.daysRemaining === null ? 'Stock lasts > 6 months' : `Approx. ${report.runwayWeeks} Weeks of supply`;
    const depDateText = report.depletionDate ? report.depletionDate : 'Indefinite / Safe';
    const depDateSub = report.baselineDepletionDate ? `Baseline Pace: ${report.baselineDepletionDate}` : 'Safe buffer';
    const seasonalText = report.seasonalSurgePct > 0 ? `+${report.seasonalSurgePct}% Surge` : 'Normal Baseline';
    const seasonalSub = `${esc(report.diseaseName)} (R² = ${report.rSquared})`;

    const card = (value, label, icon, variant, sub) => `
        <div class="kpi-card ${variant ? 'kpi-' + variant : ''}">
            <div class="kpi-header">
                <span class="kpi-label">${label}</span>
                <span class="kpi-icon">${icon}</span>
            </div>
            <div class="kpi-value" style="font-size:22px;">${value}</div>
            <div class="kpi-detail">${sub}</div>
        </div>`;

    const variant = report.riskLevel === 'CRITICAL' ? 'danger' : report.riskLevel === 'HIGH' ? 'warning' : 'primary';

    cardsEl.innerHTML =
        card(runwayText, 'Stock Duration Runway', '⏳', variant, runwaySub) +
        card(depDateText, 'Predicted Depletion Date', '📅', variant, depDateSub) +
        card(seasonalText, 'Seasonal Outbreak Surge', '🌦️', report.seasonalSurgePct > 0 ? 'warning' : 'primary', seasonalSub) +
        card(`${fmt(report.usableStock)} ${esc(report.unit)}s`, 'Current Usable Stock', '📦', 'primary', `${report.activeBatchesCount} Active Batches`) +
        card(badge(report.riskLevel), 'Stockout Risk Level', '🚨', variant, report.daysRemaining <= 14 ? 'Rebalance or Order immediately' : 'Adequate buffer remaining');
}

function renderHistoricalStats(report) {
    const box = document.getElementById('historicalStatsBox');
    if (!box) return;
    box.innerHTML = `
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 14px;">
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Last 14-Day Average</span>
                <div style="font-size: 18px; font-weight: 700; color: var(--navy-900); margin-top: 2px;">${report.avgDemandLast14Days} <span style="font-size:12px;font-weight:400;">${esc(report.unit)}s/day</span></div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Last 30-Day Average</span>
                <div style="font-size: 18px; font-weight: 700; color: var(--navy-900); margin-top: 2px;">${report.avgDemandLast30Days} <span style="font-size:12px;font-weight:400;">${esc(report.unit)}s/day</span></div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Last 60-Day Average</span>
                <div style="font-size: 18px; font-weight: 700; color: var(--navy-900); margin-top: 2px;">${report.avgDemandLast60Days} <span style="font-size:12px;font-weight:400;">${esc(report.unit)}s/day</span></div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">14-Day Velocity Trend</span>
                <div style="font-size: 18px; font-weight: 700; color: ${report.consumptionTrendPct > 0 ? '#ef4444' : '#10b981'}; margin-top: 2px;">${report.consumptionTrendPct > 0 ? '+' : ''}${report.consumptionTrendPct}%</div>
            </div>
        </div>
        <p class="muted" style="margin-top: 10px; font-size: 12px;">Baseline Holt time-series trend fits level &amp; slope with weekday seasonal weighting.</p>
    `;
}

function renderSeasonalityStats(report) {
    const box = document.getElementById('seasonalityStatsBox');
    if (!box) return;
    box.innerHTML = `
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 14px;">
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Epidemiology Driver</span>
                <div style="font-size: 15px; font-weight: 700; color: #4338ca; margin-top: 2px;">${esc(report.diseaseTag)}</div>
                <div style="font-size: 11px; color: var(--slate-500);">${esc(report.diseaseName)}</div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Caseload Regression R²</span>
                <div style="font-size: 18px; font-weight: 700; color: var(--navy-900); margin-top: 2px;">${report.rSquared} <span style="font-size:12px;font-weight:400;">(${Math.round(report.diseaseWeight * 100)}% weight)</span></div>
                <div style="font-size: 11px; color: var(--slate-500);">${report.rSquared >= 0.3 ? 'Strong epidemiological link' : 'Weak disease correlation'}</div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Next 2-Week Disease Trend</span>
                <div style="font-size: 18px; font-weight: 700; color: ${report.casesNext2WeeksChangePct > 0 ? '#ef4444' : '#10b981'}; margin-top: 2px;">${report.casesNext2WeeksChangePct > 0 ? '+' : ''}${report.casesNext2WeeksChangePct}%</div>
                <div style="font-size: 11px; color: var(--slate-500);">Past 2-week: ${report.casesPast2WeeksChangePct > 0 ? '+' : ''}${report.casesPast2WeeksChangePct}%</div>
            </div>
            <div style="background: var(--slate-100); padding: 12px 14px; border-radius: 8px;">
                <span class="muted" style="font-size:12px;">Runway Lost to Season</span>
                <div style="font-size: 18px; font-weight: 700; color: ${report.daysLostToSeason > 0 ? '#ef4444' : '#10b981'}; margin-top: 2px;">${report.daysLostToSeason ? '-' + report.daysLostToSeason + ' days' : '0 days'}</div>
                <div style="font-size: 11px; color: var(--slate-500);">${report.daysLostToSeason > 0 ? 'Accelerated stockout' : 'No seasonal loss'}</div>
            </div>
        </div>
    `;
}

function renderRunwayBatchTable(batchDetails, unit) {
    renderTable('runwayBatchTable', [
        { title: 'Batch Number', value: b => `<code>${esc(b.batchNo)}</code>` },
        { title: 'Initial Stock', num: true, value: b => `<b>${fmt(b.initialQuantity)}</b> ${esc(unit)}s` },
        { title: 'Expiry Date', value: b => b.expiryDate },
        { title: 'Shelf-Life Countdown', num: true, value: b => b.daysUntilExpiry < 0 ? `<span style="color:#ef4444;">Expired (${Math.abs(b.daysUntilExpiry)}d ago)</span>` : `${b.daysUntilExpiry} days` },
        { title: 'Units Consumed', num: true, value: b => fmt(b.unitsConsumed) },
        { title: 'Units Expired Unused', num: true, value: b => b.unitsExpiredUnused > 0 ? `<b style="color:#ef4444;">${fmt(b.unitsExpiredUnused)}</b>` : '0' },
        { title: 'Depletion Date', value: b => b.depletedDate ? `<b>${b.depletedDate}</b>` : '<span class="muted">Active buffer</span>' },
        { title: 'Condition', value: b => badge(b.status) }
    ], batchDetails, 'No batch records found.');
}

function renderRunwayChart(trajectory, asOf, simulatedTrajectory) {
    if (typeof Chart === 'undefined') return;
    const ctx = document.getElementById('runwayChart');
    if (!ctx) return;

    if (state.runwayChart) state.runwayChart.destroy();

    const labels = trajectory.map(p => p.date);
    const datasets = [
        {
            label: 'AI Seasonal Forecast (Realistic)',
            data: trajectory.map(p => p.stockAiSeasonal),
            borderColor: '#2563eb',
            backgroundColor: 'rgba(37, 99, 235, 0.08)',
            fill: true,
            borderWidth: 2.5,
            pointRadius: 0,
            tension: 0.1
        },
        {
            label: 'Baseline Pace (Non-seasonal)',
            data: trajectory.map(p => p.stockBaseline),
            borderColor: '#64748b',
            borderDash: [5, 4],
            borderWidth: 2,
            pointRadius: 0,
            tension: 0.1
        },
        {
            label: 'Epidemic Surge (+35% wave)',
            data: trajectory.map(p => p.stockSurge),
            borderColor: '#ef4444',
            borderWidth: 1.5,
            pointRadius: 0,
            tension: 0.1
        }
    ];

    if (simulatedTrajectory && simulatedTrajectory.length > 0) {
        datasets.push({
            label: '🎛️ What-If Simulated Runway',
            data: simulatedTrajectory.map(p => p.stockAiSeasonal),
            borderColor: '#10b981',
            borderWidth: 3,
            borderDash: [3, 3],
            pointRadius: 0,
            tension: 0.1
        });
    }

    state.runwayChart = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets },
        options: {
            responsive: true,
            interaction: { mode: 'index', intersect: false },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { maxTicksLimit: 12 }
                },
                y: {
                    beginAtZero: true,
                    title: { display: true, text: 'Stock Units Remaining' }
                }
            },
            plugins: {
                tooltip: {
                    callbacks: {
                        footer: function(items) {
                            const idx = items[0].dataIndex;
                            const pt = trajectory[idx];
                            return `Daily Demand: ~${pt.demandAiSeasonal} units/d (Base: ${pt.demandBaseline}/d)`;
                        }
                    }
                }
            }
        }
    });
}

function setupRunwaySliders() {
    const stockSlider = document.getElementById('simStockSlider');
    const surgeSlider = document.getElementById('simSurgeSlider');
    if (!stockSlider || !surgeSlider) return;

    const onSliderChange = debounce(async () => {
        if (!state.currentRunwayReport) return;
        const addStock = Number(stockSlider.value);
        const surgeMult = Number(surgeSlider.value);

        document.getElementById('sliderStockVal').textContent = addStock > 0 ? `+${fmt(addStock)} units` : '+0 (Current)';
        document.getElementById('sliderSurgeVal').textContent = `${surgeMult.toFixed(1)}x (${surgeMult > 1 ? '+' + Math.round((surgeMult - 1) * 100) + '% Outbreak' : surgeMult < 1 ? Math.round((surgeMult - 1) * 100) + '% Milder' : 'Actual Forecast'})`;

        try {
            const simReport = await api('POST', '/api/analytics/stock-runway/simulate', {
                district: state.district,
                facilityId: state.currentRunwayReport.facilityId,
                medicineId: state.currentRunwayReport.medicineId,
                additionalStock: addStock,
                surgeMultiplierOverride: surgeMult
            });

            // Update chart with simulated curve
            renderRunwayChart(state.currentRunwayReport.trajectory, state.currentRunwayReport.asOf, simReport.trajectory);

            // Update outcome banner
            updateSimulationBanner(state.currentRunwayReport, simReport);

        } catch (e) {
            console.error('Simulation error:', e);
        }
    }, 150);

    stockSlider.addEventListener('input', onSliderChange);
    surgeSlider.addEventListener('input', onSliderChange);
}

function updateSimulationBanner(current, sim) {
    const banner = document.getElementById('simOutcomeBanner');
    if (!banner) return;

    const curDays = current.daysRemaining ?? 180;
    const simDays = sim.daysRemaining ?? 180;
    const diff = simDays - curDays;

    let text = `<b>Simulated Prediction Outcome:</b> `;
    text += `Stock will last <b>${sim.daysRemaining !== null ? sim.daysRemaining + ' days' : '180+ days'}</b> `;
    text += `(Depletion Date: <b>${sim.depletionDate || 'Indefinite'}</b>) vs actual <b>${current.daysRemaining !== null ? current.daysRemaining + ' days' : '180+ days'}</b> (${current.depletionDate || 'Safe'}). `;

    if (diff > 0) {
        text += `<span style="color: #4ade80; font-weight:700;">▲ Extends stock duration by +${diff} days (+${(diff / 7).toFixed(1)} weeks)!</span>`;
    } else if (diff < 0) {
        text += `<span style="color: #f87171; font-weight:700;">▼ Stock runs out ${Math.abs(diff)} days sooner due to heightened surge!</span>`;
    } else {
        text += `<span>Baseline conditions unchanged.</span>`;
    }

    banner.innerHTML = text;
}

function resetRunwaySimulation() {
    const stockSlider = document.getElementById('simStockSlider');
    const surgeSlider = document.getElementById('simSurgeSlider');
    if (stockSlider) stockSlider.value = 0;
    if (surgeSlider) surgeSlider.value = 1.0;
    document.getElementById('sliderStockVal').textContent = '+0 units';
    document.getElementById('sliderSurgeVal').textContent = '1.0x (Actual Forecast)';
    if (state.currentRunwayReport) {
        renderRunwayChart(state.currentRunwayReport.trajectory, state.currentRunwayReport.asOf, null);
        updateSimulationBanner(state.currentRunwayReport, state.currentRunwayReport);
    }
}

function debounce(fn, ms) {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
}

// ---------------------------------------------------------------- 6. Leaflet Map & One-Trip Delivery Route Planner
let mapInitialized = false;

async function loadMapTab() {
    const subtitle = document.getElementById('mapSubtitle');
    if (subtitle) subtitle.textContent = `Loading geospatial health network and transfers for ${state.district}...`;

    initFacilityMap();

    setTimeout(() => {
        if (state.leafletMap) state.leafletMap.invalidateSize();
    }, 200);

    await loadFacilityNetwork();
}

function initFacilityMap() {
    const mapEl = document.getElementById('facilityMap');
    if (!mapEl || typeof L === 'undefined') return;
    if (mapInitialized && state.leafletMap) return;
    if (mapEl._leaflet_id && state.leafletMap) return;

    try {
        state.leafletMap = L.map('facilityMap', { zoomControl: true }).setView([12.9165, 79.1325], 11);

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(state.leafletMap);

        state.mapLayers.markers = L.layerGroup().addTo(state.leafletMap);
        state.mapLayers.transfers = L.layerGroup().addTo(state.leafletMap);
        state.mapLayers.route = L.layerGroup().addTo(state.leafletMap);

        mapInitialized = true;
    } catch (err) {
        console.warn('Map initialization note:', err);
    }
}

async function loadFacilityNetwork() {
    try {
        const data = await api('GET', '/api/map/facility-network?district=' + encodeURIComponent(state.district));
        state.networkData = data;

        const subtitle = document.getElementById('mapSubtitle');
        if (subtitle) {
            subtitle.textContent = `${data.district} District • ${data.facilities.length} Public Health Facilities • ${data.transfersCount} Recommended Inter-Facility Transfers`;
        }

        renderMapNetwork(data);

        // Auto-plan delivery trip if transfers exist
        if (data.transfersCount > 0) {
            planDeliveryTrip();
        } else {
            document.getElementById('routeSavingsBanner')?.classList.add('hidden');
            renderTable('routeItineraryTable', [
                { title: 'Stop #', value: () => '-' },
                { title: 'Facility', value: () => 'All facilities balanced' },
                { title: 'Action', value: () => 'No redistribution transfers needed' }
            ], [], 'No transfers required in this district.');
        }

    } catch (e) {
        console.error('Failed to load facility network:', e);
        const subtitle = document.getElementById('mapSubtitle');
        if (subtitle) subtitle.textContent = 'Error loading facility network: ' + e.message;
    }
}

function renderMapNetwork(data) {
    if (!state.leafletMap || !state.mapLayers.markers) return;

    state.mapLayers.markers.clearLayers();
    state.mapLayers.transfers.clearLayers();

    const bounds = [];

    // 1. Draw Facility Markers
    data.facilities.forEach(f => {
        const color = f.riskLevel === 'CRITICAL' ? '#ef4444'
            : f.riskLevel === 'HIGH' ? '#f97316'
            : f.riskLevel === 'MEDIUM' ? '#eab308' : '#10b981';

        const radius = f.type === 'DH' ? 13 : f.type === 'CHC' ? 10 : 8;

        // Animated Radar Beacon wave for Critical facilities
        if (f.riskLevel === 'CRITICAL') {
            const pulseIcon = L.divIcon({
                className: 'pulse-marker-wrapper',
                html: `<div class="pulse-beacon"></div><div class="pulse-core"></div>`,
                iconSize: [26, 26],
                iconAnchor: [13, 13]
            });
            const pulseMarker = L.marker([f.latitude, f.longitude], { icon: pulseIcon, zIndexOffset: -100 });
            state.mapLayers.markers.addLayer(pulseMarker);
        }

        const marker = L.circleMarker([f.latitude, f.longitude], {
            radius,
            fillColor: color,
            color: '#ffffff',
            weight: 2.5,
            fillOpacity: 0.95
        });

        const critHtml = f.criticalMedicines && f.criticalMedicines.length > 0
            ? `<div style="margin-top:6px; font-size:11.5px; color:#ef4444;"><b>🚨 Stockout Alerts:</b><br/>• ${f.criticalMedicines.map(esc).join('<br/>• ')}</div>`
            : '';

        const surplusHtml = f.surplusMedicines && f.surplusMedicines.length > 0
            ? `<div style="margin-top:6px; font-size:11.5px; color:#10b981;"><b>🟢 Surplus Available:</b><br/>• ${f.surplusMedicines.map(esc).join('<br/>• ')}</div>`
            : '';

        const popupContent = `
            <div style="font-family: Inter, sans-serif; min-width: 220px; line-height:1.45;">
                <div style="font-weight: 700; font-size: 14px; color:#0f172a; margin-bottom: 2px;">${esc(f.name)}</div>
                <div style="font-size: 11px; color: #64748b; margin-bottom: 6px;"><code>${esc(f.code)}</code> • ${esc(f.type)} • Catchment: ${fmt(f.population)}</div>
                <div style="margin-bottom: 6px;">Status: ${badge(f.riskLevel)}</div>
                <div style="font-size: 12px; color: #334155;">Usable Inventory: <b>${fmt(f.totalUsableStock)} units</b></div>
                ${critHtml}
                ${surplusHtml}
            </div>
        `;

        marker.bindPopup(popupContent);
        marker.bindTooltip(`<b>${esc(f.name)}</b> (${esc(f.riskLevel)})`, { direction: 'top', offset: [0, -8] });

        state.mapLayers.markers.addLayer(marker);
        bounds.push([f.latitude, f.longitude]);
    });

    // 2. Draw Transfer Vectors (Animated Marching Ant Purple Lines)
    data.transfers.forEach(t => {
        const line = L.polyline([[t.fromLat, t.fromLon], [t.toLat, t.toLon]], {
            className: 'animated-transfer-line',
            color: '#8b5cf6',
            weight: 3.5,
            dashArray: '8, 8',
            opacity: 0.95
        });

        const popupContent = `
            <div style="font-family: Inter, sans-serif; min-width: 230px; font-size: 12.5px;">
                <div style="font-weight: 700; color: #6d28d9; margin-bottom: 4px;">🚚 Recommended Transfer Vector</div>
                <div><b>From:</b> ${esc(t.fromFacility)}</div>
                <div><b>To:</b> ${esc(t.toFacility)}</div>
                <div><b>Payload:</b> <b>${fmt(t.quantity)} ${esc(t.unit)}s</b> ${esc(t.medicineName)}</div>
                <div><b>Distance:</b> ${t.distanceKm} km</div>
                <div style="margin-top: 4px;">Urgency: ${badge(t.urgency)}</div>
                <div class="why" style="margin-top: 6px; font-size: 11px;">${esc(t.rationale)}</div>
            </div>
        `;

        line.bindPopup(popupContent);
        state.mapLayers.transfers.addLayer(line);
    });

    // Fit map bounds
    if (bounds.length > 0) {
        state.leafletMap.fitBounds(bounds, { padding: [45, 45] });
        setTimeout(() => {
            if (state.leafletMap) {
                state.leafletMap.invalidateSize();
                state.leafletMap.fitBounds(bounds, { padding: [45, 45] });
            }
        }, 150);
    }
}

async function planDeliveryTrip() {
    const btn = document.querySelector('#tab-map .btn-primary');
    if (btn) { btn.disabled = true; btn.textContent = '⏳ Calculating Route...'; }

    try {
        const capacity = Number(document.getElementById('vanCapacityInput')?.value) || 5000;
        const plan = await api('POST', `/api/routes/plan?district=${encodeURIComponent(state.district)}&vanCapacity=${capacity}`);
        state.routePlan = plan;

        renderDeliveryRoute(plan);
        showToast('Delivery Trip Planned', `Consolidated route created: ${plan.kmSaved} km (${plan.pctSaved}%) saved!`, 'success');

    } catch (e) {
        alert('Could not calculate delivery route: ' + e.message);
    } finally {
        if (btn) { btn.disabled = false; btn.textContent = '⚡ Plan Delivery Trip'; }
    }
}

function renderDeliveryRoute(plan) {
    if (!state.leafletMap || !state.mapLayers.route) return;

    state.mapLayers.route.clearLayers();

    // 1. Draw Route Line (Bold Royal Blue with Soft Glow)
    if (plan.polylineCoordinates && plan.polylineCoordinates.length > 1) {
        const routeLine = L.polyline(plan.polylineCoordinates, {
            className: 'animated-route-line',
            color: '#2563eb',
            weight: 5.5,
            opacity: 0.95,
            lineJoin: 'round'
        });

        routeLine.bindPopup(`<b>One-Trip Delivery Route</b><br/>Total Distance: <b>${plan.combinedRouteKm} km</b><br/>Kilometers Saved: <b>${plan.kmSaved} km (-${plan.pctSaved}%)</b>`);
        state.mapLayers.route.addLayer(routeLine);
    }

    // 2. Add Stop Markers (Numbered Badges)
    plan.stops.forEach((s, i) => {
        const badgeColor = i === 0 ? '#10b981' : (i === plan.stops.length - 1 ? '#0f172a' : '#2563eb');
        const badgeText = i === 0 ? 'START' : (i === plan.stops.length - 1 ? 'END' : String(s.stopNumber));

        const stopIcon = L.divIcon({
            className: 'custom-stop-badge',
            html: `<div style="background:${badgeColor}; color:white; font-size:11px; font-weight:700; width:26px; height:26px; border-radius:50%; display:flex; align-items:center; justify-content:center; box-shadow:0 2px 6px rgba(0,0,0,0.3); border:2px solid white;">${badgeText}</div>`,
            iconSize: [26, 26],
            iconAnchor: [13, 13]
        });

        const marker = L.marker([s.latitude, s.longitude], { icon: stopIcon });
        marker.bindPopup(`
            <div style="font-family: Inter, sans-serif; font-size: 12.5px;">
                <div style="font-weight: 700; font-size: 13.5px; color:#1e293b;">Stop #${s.stopNumber}: ${esc(s.facilityName)}</div>
                <div style="color: #64748b; font-size: 11px; margin-bottom: 4px;">Leg: +${s.segmentDistanceKm} km | Cumulative: ${s.cumulativeDistanceKm} km</div>
                <div style="margin: 6px 0; background:#f1f5f9; padding:6px 8px; border-radius:6px; font-size:11.5px;">${esc(s.action)}</div>
                <div>Van Cargo Onboard: <b>${fmt(s.vanCargoAfterStop)} / ${fmt(plan.vanCapacity)} units</b></div>
            </div>
        `);
        state.mapLayers.route.addLayer(marker);
    });

    // 3. Render Route Impact Summary Banner
    const banner = document.getElementById('routeSavingsBanner');
    if (banner) {
        banner.classList.remove('hidden');
        document.getElementById('routeSavingsBadge').textContent = `${plan.kmSaved} KM SAVED (-${plan.pctSaved}%)`;
        document.getElementById('routeMissionSummary').textContent = 
            `Combined ${plan.totalTransfersFulfilled} separate redistribution trips into 1 single multi-stop vehicle route. Peak van load was ${fmt(plan.peakVanLoad)} of ${fmt(plan.vanCapacity)} units capacity.`;

        const metricsGrid = document.getElementById('routeMetricsGrid');
        if (metricsGrid) {
            metricsGrid.innerHTML = `
                <div style="background: rgba(255,255,255,0.08); padding: 8px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);">
                    <div style="font-size: 11px; color: #94a3b8;">Combined Route</div>
                    <div style="font-size: 18px; font-weight: 700; color: #38bdf8;">${plan.combinedRouteKm} km</div>
                </div>
                <div style="background: rgba(255,255,255,0.08); padding: 8px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);">
                    <div style="font-size: 11px; color: #94a3b8;">Separate Round Trips</div>
                    <div style="font-size: 18px; font-weight: 700; color: #e2e8f0; text-decoration: line-through;">${plan.separateTripsKm} km</div>
                </div>
                <div style="background: rgba(16, 185, 129, 0.2); padding: 8px 14px; border-radius: 8px; border: 1px solid rgba(16, 185, 129, 0.4);">
                    <div style="font-size: 11px; color: #6ee7b7;">Distance Saved</div>
                    <div style="font-size: 18px; font-weight: 700; color: #34d399;">${plan.kmSaved} km</div>
                </div>
                <div style="background: rgba(255,255,255,0.08); padding: 8px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);">
                    <div style="font-size: 11px; color: #94a3b8;">Fuel Saved</div>
                    <div style="font-size: 18px; font-weight: 700; color: #fbbf24;">~${plan.fuelSavedLitres} L</div>
                </div>
                <div style="background: rgba(255,255,255,0.08); padding: 8px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);">
                    <div style="font-size: 11px; color: #94a3b8;">CO₂ Prevented</div>
                    <div style="font-size: 18px; font-weight: 700; color: #a78bfa;">~${plan.co2SavedKg} kg</div>
                </div>
            `;
        }
    }

    // 4. Render Turn-by-Turn Delivery Manifest Table
    renderTable('routeItineraryTable', [
        { title: 'Stop #', value: s => `<b>#${s.stopNumber}</b>` },
        { title: 'Facility', value: s => `<b>${esc(s.facilityName)}</b> <span class="badge ${esc(s.facilityType)}">${esc(s.facilityType)}</span>` },
        { title: 'Vehicle Manifest Action', value: s => `<div style="font-weight:600; color:${s.action.startsWith('START') ? '#10b981' : s.action.startsWith('END') ? '#0f172a' : '#1e293b'};">${esc(s.action)}</div>` },
        { title: 'Van Load Onboard', num: true, value: s => `<b>${fmt(s.vanCargoAfterStop)}</b> / ${fmt(plan.vanCapacity)}` },
        { title: 'Leg Distance', num: true, value: s => `+${s.segmentDistanceKm} km` },
        { title: 'Cumulative Distance', num: true, value: s => `<b>${s.cumulativeDistanceKm} km</b>` },
        { title: 'Action', value: s => `<button class="small" onclick="focusMapStop(${s.latitude}, ${s.longitude})">📍 Focus</button>` }
    ], plan.stops, 'No route stops generated.');
}

function focusMapStop(lat, lon) {
    if (state.leafletMap) {
        state.leafletMap.setView([lat, lon], 14, { animate: true });
    }
}

function resetMapView() {
    if (!state.leafletMap || !state.networkData || !state.networkData.facilities) return;
    const bounds = state.networkData.facilities.map(f => [f.latitude, f.longitude]);
    if (bounds.length > 0) {
        state.leafletMap.fitBounds(bounds, { padding: [40, 40] });
    }
}

function toggleMapLayer(layerName) {
    if (!state.leafletMap) return;
    if (layerName === 'transfers') {
        const checked = document.getElementById('toggleTransfers').checked;
        if (checked) state.leafletMap.addLayer(state.mapLayers.transfers);
        else state.leafletMap.removeLayer(state.mapLayers.transfers);
    } else if (layerName === 'route') {
        const checked = document.getElementById('toggleRoute').checked;
        if (checked) state.leafletMap.addLayer(state.mapLayers.route);
        else state.leafletMap.removeLayer(state.mapLayers.route);
    }
}

// Start application
init();
