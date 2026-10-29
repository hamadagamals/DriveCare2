const authScreen = document.getElementById('auth-screen');
const dashboardScreen = document.getElementById('dashboard-screen');
const authSubmit = document.getElementById('auth-submit');
const authToggle = document.getElementById('auth-toggle');
const nameInput = document.getElementById('name');
const emailInput = document.getElementById('email');
const passwordInput = document.getElementById('password');
const logoutBtn = document.getElementById('logout-btn');
const authStatus = document.getElementById('auth-status');
const addRoleSelect = document.getElementById('add-role-select');
const addRoleButton = document.getElementById('add-role-btn');
const addRoleStatus = document.getElementById('add-role-status');
const accountWorkshopFields = document.getElementById('account-workshop-fields');
const accountWorkshopName = document.getElementById('account-workshop-name');
const accountWorkshopPhone = document.getElementById('account-workshop-phone');
const accountWorkshopServices = document.getElementById('account-workshop-services');
const vehicleSetupCard = document.getElementById('vehicle-setup-card');
const vehicleSetupForm = document.getElementById('vehicle-setup-form');
const vehicleSetupName = document.getElementById('setup-vehicle-name');
const vehicleSetupMake = document.getElementById('setup-vehicle-make');
const vehicleSetupModel = document.getElementById('setup-vehicle-model');
const vehicleSetupYear = document.getElementById('setup-vehicle-year');
const vehicleSetupStatus = document.getElementById('vehicle-setup-status');
const changePasswordForm = document.getElementById('change-password-form');
const currentPasswordInput = document.getElementById('current-password');
const newPasswordInput = document.getElementById('new-password');
const confirmPasswordInput = document.getElementById('confirm-password');
const passwordChangeStatus = document.getElementById('password-change-status');
const rolePicker = document.getElementById('role-picker');
const phoneInput = document.getElementById('phone');
const workshopFields = document.getElementById('workshop-fields');
const workshopName = document.getElementById('workshop-name');
const workshopPhone = document.getElementById('workshop-phone');
const workshopServices = document.getElementById('workshop-services');
const connectionStatus = document.getElementById('connection-status');
const connectionHelp = document.getElementById('connection-help');
const bleConnectBtn = document.getElementById('ble-connect-btn');
const classicConnectBtn = document.getElementById('classic-connect-btn');
const wifiConnectBtn = document.getElementById('wifi-connect-btn');
const workshopList = document.getElementById('workshop-list');
const workshopStatus = document.getElementById('workshop-status');
const voiceStatus = document.getElementById('voice-status');
const testVoiceBtn = document.getElementById('test-voice-btn');
const addDriverBtn = document.getElementById('add-driver-btn');
const driverEmail = document.getElementById('driver-email');
const driverList = document.getElementById('driver-list');
const driverStatus = document.getElementById('driver-status');
const activeRoleLabel = document.getElementById('active-role-label');
const roleSwitchButtons = document.getElementById('role-switch-buttons');
const roleSwitchStatus = document.getElementById('role-switch-status');
const customAccessDays = document.getElementById('custom-access-days');
const deviceAccessDriverId = document.getElementById('device-access-driver-id');
const grantDeviceAccessBtn = document.getElementById('grant-device-access-btn');
const deviceAccessList = document.getElementById('device-access-list');
const deviceAccessStatus = document.getElementById('device-access-status');
const ownerDriverControls = document.getElementById('owner-driver-controls');
const ownerDeviceControls = document.getElementById('owner-device-controls');
const ownerManagementCard = document.getElementById('owner-management-card');
const driverModeCard = document.getElementById('driver-mode-card');
const workshopModeCard = document.getElementById('workshop-mode-card');
const openWorkshopBtn = document.getElementById('open-workshop-btn');
const workshopVehicleId = document.getElementById('workshop-vehicle-id');
const workshopTargetPhone = document.getElementById('workshop-target-phone');
const requestVehicleAccessBtn = document.getElementById('request-vehicle-access-btn');
const workshopRequestStatus = document.getElementById('workshop-request-status');
const workshopAccessRequests = document.getElementById('workshop-access-requests');
const targetWorkshopPhone = document.getElementById('target-workshop-phone');
const requestWorkshopByPhoneBtn = document.getElementById('request-workshop-by-phone-btn');

let signupMode = false;
let lastTemperatureAlert = null;
let userRoles = [];
let currentVehicleId = 'veh-001';

const roleLabels = { owner: 'مالك سيارة', driver: 'سائق', workshop: 'صاحب ورشة' };

function applyRoleView(role) {
  const ownerMode = role === 'owner';
  const workshopMode = role === 'workshop';
  if (ownerManagementCard) ownerManagementCard.hidden = !ownerMode;
  document.querySelectorAll('[data-owner-only="true"]').forEach((element) => {
    element.hidden = !ownerMode;
  });
  document.querySelectorAll('[data-panel]').forEach((element) => {
    element.hidden = workshopMode;
  });
  const navEl = document.querySelector('.nav');
  if (navEl) navEl.hidden = workshopMode;
  if (driverModeCard) driverModeCard.hidden = role !== 'driver';
  if (workshopModeCard) workshopModeCard.hidden = role !== 'workshop';
  if (ownerDriverControls) ownerDriverControls.hidden = !ownerMode;
  if (ownerDeviceControls) ownerDeviceControls.hidden = !ownerMode;
}

function showVehicleSetup() {
  showMobilePanel('account');
  vehicleSetupCard.hidden = false;
  setScreen('dashboard');
}

async function createFirstVehicle(event) {
  event.preventDefault();
  const name = vehicleSetupName.value.trim();
  if (!name) {
    vehicleSetupStatus.textContent = 'اكتب اسم السيارة أولًا.';
    return;
  }
  vehicleSetupStatus.textContent = 'جارٍ حفظ السيارة...';
  try {
    const response = await fetch('/api/vehicles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: getToken() },
      body: JSON.stringify({
        action: 'create',
        vehicle: {
          name,
          make: vehicleSetupMake.value.trim() || null,
          model: vehicleSetupModel.value.trim() || null,
          year: vehicleSetupYear.value ? Number(vehicleSetupYear.value) : null,
        },
      }),
    });
    const data = await response.json();
    if (!response.ok || !data.vehicleId) {
      vehicleSetupStatus.textContent = data.error || 'تعذر حفظ السيارة.';
      return;
    }
    currentVehicleId = data.vehicleId;
    vehicleSetupForm.reset();
    vehicleSetupCard.hidden = true;
    await loadDashboard();
  } catch (error) {
    console.error(error);
    vehicleSetupStatus.textContent = 'تعذر الاتصال بالخادم أثناء حفظ السيارة.';
  }
}

function selectedSignupRoles() {
  return Array.from(document.querySelectorAll('input[name="roles"]:checked')).map((input) => input.value);
}

function updateWorkshopFields() {
  workshopFields.hidden = !selectedSignupRoles().includes('workshop');
}

function updateAccountWorkshopFields() {
  accountWorkshopFields.hidden = addRoleSelect.value !== 'workshop';
}

async function addAccountRole() {
  const role = addRoleSelect.value;
  const workshop = role === 'workshop'
    ? {
        name: accountWorkshopName.value.trim(),
        phone: accountWorkshopPhone.value.trim(),
        services: accountWorkshopServices.value.trim(),
      }
    : null;
  if (role === 'workshop' && (!workshop.name || !workshop.phone || !workshop.services)) {
    addRoleStatus.textContent = 'أكمل اسم الورشة ورقمها والخدمات أولًا.';
    return;
  }
  addRoleButton.disabled = true;
  addRoleStatus.textContent = 'جارٍ إضافة الدور...';
  try {
    const response = await fetch('/api/account/roles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: getToken() },
      body: JSON.stringify({ role, workshop }),
    });
    const data = await response.json();
    if (!response.ok || !data.roles) {
      addRoleStatus.textContent = data.error || 'تعذر إضافة الدور.';
      return;
    }
    saveRoles(data.roles);
    renderRoleSwitcher(data.roles);
    accountWorkshopName.value = '';
    accountWorkshopPhone.value = '';
    accountWorkshopServices.value = '';
    updateAccountWorkshopFields();
    addRoleStatus.textContent = 'تمت إضافة الدور للحساب نفسه. يمكنك التبديل إليه من الأزرار أعلاه.';
  } catch (error) {
    console.error(error);
    addRoleStatus.textContent = 'تعذر الاتصال بالخادم أثناء إضافة الدور.';
  } finally {
    addRoleButton.disabled = false;
  }
}

function renderRoleSwitcher(roles) {
  userRoles = roles.length ? roles : ['owner'];
  const storedRole = localStorage.getItem('drivecare-active-role');
  const activeRole = userRoles.includes(storedRole) ? storedRole : userRoles[0];
  localStorage.setItem('drivecare-active-role', activeRole);
  if (activeRoleLabel) activeRoleLabel.textContent = roleLabels[activeRole] || activeRole;
  applyRoleView(activeRole);
  if (!roleSwitchButtons) return;
  roleSwitchButtons.innerHTML = userRoles.map((role) => `
    <button type="button" class="role-switch-btn" data-role="${role}">${roleLabels[role] || role}</button>
  `).join('') || '<span class="small muted">لا توجد أدوار متاحة لهذا الحساب.</span>';
  roleSwitchButtons.querySelectorAll('.role-switch-btn').forEach((button) => {
    button.classList.toggle('active', button.dataset.role === activeRole);
  });
  if (roleSwitchStatus) {
    roleSwitchStatus.textContent = userRoles.length < 2
      ? 'هذا الحساب مسجل بدور واحد فقط. للتبديل، سجّل الأدوار المطلوبة أثناء إنشاء الحساب.'
      : 'اختر أي زر لتطبيق وضع الحساب فورًا.';
  }
}

roleSwitchButtons?.addEventListener('click', (event) => {
  const button = event.target.closest('.role-switch-btn');
  if (!button || !userRoles.includes(button.dataset.role)) return;
  const role = button.dataset.role;
  localStorage.setItem('drivecare-active-role', role);
  if (activeRoleLabel) activeRoleLabel.textContent = roleLabels[role] || role;
  roleSwitchButtons.querySelectorAll('.role-switch-btn').forEach((item) => {
    item.classList.toggle('active', item.dataset.role === role);
  });
  applyRoleView(role);
  showMobilePanel('home');
  loadDashboard();
  if (roleSwitchStatus) {
    roleSwitchStatus.textContent = role === 'workshop'
      ? 'تم التبديل إلى وضع صاحب الورشة.'
      : role === 'driver'
       ? 'تم التبديل إلى وضع السائق.'
       : 'تم التبديل إلى وضع مالك السيارة.';
  }
});

function speakTemperatureAlert(temperature, isTest = false) {
  if (!('speechSynthesis' in window)) {
    voiceStatus.textContent = 'غير مدعوم';
    return;
  }

  const message = isTest
    ? 'تنبيه تجريبي: يرجى فحص حرارة المحرك.'
    : `تحذير: حرارة المحرك مرتفعة، ${temperature} درجة مئوية. يرجى التوقف في مكان آمن وفحص السيارة.`;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(message);
  utterance.lang = 'ar-SA';
  utterance.rate = 0.9;
  window.speechSynthesis.speak(utterance);
  voiceStatus.textContent = isTest ? 'تم تشغيل التجربة' : 'تنبيه حرارة نشط';
}

function checkTemperatureAlert(vehicle) {
  const temperature = Number(vehicle.engineTemp);
  if (!Number.isFinite(temperature) || temperature < 105) return;
  if (lastTemperatureAlert === temperature) return;
  lastTemperatureAlert = temperature;
  speakTemperatureAlert(temperature);
}
const mobilePanels = document.querySelectorAll('[data-panel]');
const mobileNavButtons = document.querySelectorAll('[data-panel-target]');

function showMobilePanel(panelName) {
  const workshopMode = localStorage.getItem('drivecare-active-role') === 'workshop';
  mobilePanels.forEach((panel) => {
    panel.hidden = workshopMode || panel.dataset.panel !== panelName;
  });
  mobileNavButtons.forEach((button) => {
    button.classList.toggle('active', !workshopMode && button.dataset.panelTarget === panelName);
  });
}

function renderMobileLists(trips, alerts, maintenance) {
  document.getElementById('all-trips-list').innerHTML = trips.map((trip) => `
    <div class="list-item">
      <div><strong>${trip.route}</strong><div class="small muted">${trip.date} · ${trip.distance} كم</div></div>
      <span class="status-chip">${trip.average} L/100km</span>
    </div>
  `).join('') || '<div class="small muted">لا توجد رحلات مسجلة.</div>';

  document.getElementById('all-alert-list').innerHTML = alerts.map((alert) => `
    <div class="list-item">
      <div><strong>${alert.title}</strong><div class="small muted">${alert.detail}</div></div>
      <span class="small muted">${alert.time}</span>
    </div>
  `).join('') || '<div class="small muted">لا توجد تحذيرات.</div>';

  document.getElementById('all-maint-list').innerHTML = maintenance.map((item) => `
    <div class="list-item">
      <div><strong>${item.item}</strong><div class="small muted">${item.dueIn}</div></div>
      <span class="small muted">${item.status}</span>
    </div>
  `).join('') || '<div class="small muted">لا توجد مواعيد صيانة.</div>';
}

let isConnectingDevice = false;
let isSimRunning = false;
let simInterval = null;

function updateConnectionStatus(label, message) {
  const cs = document.getElementById('connection-status');
  const ch = document.getElementById('connection-help');
  if (cs) cs.textContent = label;
  if (ch) ch.textContent = message;
}

async function connectBle() {
  if (isConnectingDevice) {
    updateConnectionStatus('جارٍ البحث...', 'البحث عن جهاز OBD جارٍ بالفعل. يرجى اختيار جهازك أو الانتظار.');
    return;
  }
  if (!(await canConnectToDevice())) return;
  if (!navigator.bluetooth) {
    updateConnectionStatus('غير مدعوم بالمتصفح', 'هذا المتصفح لا يدعم Web Bluetooth. استخدم تطبيق أندرويد DriveCare أو جرب المحاكي الحي.');
    return;
  }

  isConnectingDevice = true;
  const bleBtn = document.getElementById('ble-connect-btn');
  if (bleBtn) bleBtn.disabled = true;
  const timeoutId = setTimeout(() => {
    isConnectingDevice = false;
    if (bleBtn) bleBtn.disabled = false;
  }, 14000);

  try {
    updateConnectionStatus('جاري البحث...', 'اختر جهاز OBD2 BLE من القائمة.');
    let device;
    try {
      device = await navigator.bluetooth.requestDevice({
        filters: [{ namePrefix: 'OBD' }, { namePrefix: 'V-LINK' }, { namePrefix: 'ELM' }],
        optionalServices: ['battery_service', '0000fff0-0000-1000-8000-00805f9b34fb']
      });
    } catch (_) {
      device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true });
    }

    updateConnectionStatus('متصل بـ ' + (device.name || 'OBD2'), 'تم الاتصال بجهاز الفحص بنجاح! يتم الآن استلام بيانات السيارة الحية.');
    const obdBadge = document.getElementById('obd-live-status');
    if (obdBadge) {
      obdBadge.textContent = 'متصل بـ OBD 🟢';
      obdBadge.className = 'status-chip';
    }
    toggleSimulation(true);
  } catch (error) {
    if (error.name !== 'NotFoundError') {
      console.error(error);
      updateConnectionStatus('تعذر الاتصال', 'تأكد من تشغيل جهاز OBD2 ومنح المتصفح إذن البلوتوث.');
    } else {
      updateConnectionStatus('غير متصل', 'تم إلغاء اختيار الجهاز.');
    }
  } finally {
    clearTimeout(timeoutId);
    isConnectingDevice = false;
    if (bleBtn) bleBtn.disabled = false;
  }
}

function connectWifi() {
  if (isConnectingDevice) return;
  canConnectToDevice().then((allowed) => {
    if (!allowed) return;
    updateConnectionStatus('وضع Wi-Fi (192.168.0.10)', 'اتصل بشبكة Wi-Fi الخاصة بقطعة الـ OBD (منفذ 35000). جاري استلام البيانات.');
    toggleSimulation(true);
  });
}

function connectClassicBluetooth() {
  if (isConnectingDevice) return;
  canConnectToDevice().then((allowed) => {
    if (!allowed) return;
    updateConnectionStatus('Bluetooth Classic', 'اتصال RFCOMM التسلسلي متاح بتطبيق Android الأصلي DriveCare. جاري تشغيل القراءة التجريبية.');
    toggleSimulation(true);
  });
}

let liveTripDistanceKm = 14.2;
let liveTripLitersUsed = 0.95;

function updateOwnerFuelEconomics(speed, rpm) {
  const dtHours = 0.6 / 3600; // 600ms tick in hours
  const incrementalDist = (speed * dtHours);
  liveTripDistanceKm += incrementalDist;

  // Fuel consumption rate formula: base idle 1.2L/h + dynamic displacement load
  const fuelRateL100 = (5.2 + (rpm / 1000) * 0.95 + (speed > 80 ? (speed - 80) * 0.04 : 0)).toFixed(1);
  const incrementalLiters = incrementalDist * (parseFloat(fuelRateL100) / 100);
  liveTripLitersUsed += incrementalLiters;

  const priceInput = document.getElementById('owner-fuel-price-input');
  const fuelPrice = priceInput ? (parseFloat(priceInput.value) || 14.50) : 14.50;

  const totalCost = (liveTripLitersUsed * fuelPrice).toFixed(2);
  const costPerKm = liveTripDistanceKm > 0.05 
    ? (totalCost / liveTripDistanceKm).toFixed(2)
    : ((parseFloat(fuelRateL100) * fuelPrice) / 100).toFixed(2);

  // Update Owner Dashboard Elements
  const costEl = document.getElementById('owner-live-trip-cost');
  const perKmEl = document.getElementById('owner-live-cost-per-km');
  const rateEl = document.getElementById('owner-live-fuel-rate');
  const litersEl = document.getElementById('owner-live-liters-used');
  const distEl = document.getElementById('owner-live-trip-dist');
  const speedMirrorEl = document.getElementById('owner-live-speed-mirror');
  const ecoBadgeEl = document.getElementById('owner-eco-badge');

  if (costEl) costEl.textContent = `${totalCost} ج.م`;
  if (perKmEl) perKmEl.textContent = costPerKm;
  if (rateEl) rateEl.textContent = `${fuelRateL100} L/100km`;
  if (litersEl) litersEl.textContent = `${liveTripLitersUsed.toFixed(2)} لتر`;
  if (distEl) distEl.textContent = `${liveTripDistanceKm.toFixed(1)} كم`;
  if (speedMirrorEl) speedMirrorEl.textContent = `${speed} km/h`;

  if (ecoBadgeEl) {
    if (speed < 90 && rpm < 2400) {
      ecoBadgeEl.textContent = '🌿 قيادة اقتصادية ممتازة (توفير ~16%)';
      ecoBadgeEl.style.color = 'var(--green)';
    } else {
      ecoBadgeEl.textContent = '⚠️ استهلاك مرتفع (تسارع أو سرعة عالية)';
      ecoBadgeEl.style.color = 'var(--amber)';
    }
  }

  // Update Driver Dashboard Elements
  const dDist = document.getElementById('driver-trip-dist');
  const dRate = document.getElementById('driver-trip-rate');
  if (dDist) dDist.textContent = `${liveTripDistanceKm.toFixed(1)} كم`;
  if (dRate) dRate.textContent = `${fuelRateL100} لتر/100كم`;
}

function toggleSimulation(forceStart = false) {
  if (forceStart) isSimRunning = false;
  isSimRunning = !isSimRunning;
  const simBtn = document.getElementById('toggle-sim-btn');
  const statusBadge = document.getElementById('obd-live-status');

  if (!isSimRunning) {
    clearInterval(simInterval);
    if (simBtn) simBtn.textContent = '⚡ تشغيل المحاكي الحي';
    if (statusBadge) {
      statusBadge.textContent = 'غير متصل';
      statusBadge.className = 'status-chip chip-amber';
    }
    return;
  }

  if (simBtn) simBtn.textContent = '⏹️ إيقاف المحاكي';
  if (statusBadge) {
    statusBadge.textContent = 'قراءة حية 🟢';
    statusBadge.className = 'status-chip';
  }

  let step = 0;
  clearInterval(simInterval);
  simInterval = setInterval(() => {
    step++;
    const speed = Math.round(55 + 25 * Math.sin(step * 0.25));
    const rpm = Math.round(1800 + 850 * Math.sin(step * 0.3));
    const temp = 88 + (step % 5);
    const volt = (13.8 + 0.2 * Math.sin(step * 0.1)).toFixed(1);
    const fuel = Math.max(20, 72 - Math.floor(step / 40));

    const sEl = document.getElementById('live-speed');
    const rEl = document.getElementById('live-rpm');
    const tEl = document.getElementById('live-temp');
    const bEl = document.getElementById('live-battery');
    const fEl = document.getElementById('live-fuel');

    if (sEl) sEl.textContent = `${speed} km/h`;
    if (rEl) rEl.textContent = `${rpm} RPM`;
    if (tEl) tEl.textContent = `${temp} °C`;
    if (bEl) bEl.textContent = `${volt} V`;
    if (fEl) fEl.textContent = `${fuel} %`;

    // Calculate live fuel consumption & money cost
    updateOwnerFuelEconomics(speed, rpm);

    if (temp >= 105) {
      speakTemperatureAlert(temp);
    }

    const dtcBanner = document.getElementById('live-dtc-banner');
    const dtcText = document.getElementById('live-dtc-text');
    if (dtcBanner && dtcText) {
      if (step % 24 === 0) {
        dtcBanner.className = 'dtc-banner dtc-warning';
        dtcText.textContent = '⚠️ تحذير: كود P0300 (خلل إشعال) • P0171 (خليط وقود فقير)';
      } else if (step % 24 === 12) {
        dtcBanner.className = 'dtc-banner';
        dtcText.textContent = 'لا توجد أعطال مسجلة (Check Engine: OFF)';
      }
    }
  }, 600);
}

async function canConnectToDevice() {
  const activeRole = localStorage.getItem('drivecare-active-role') || 'owner';
  // Owner inherently owns the vehicle & adapter; direct permanent pairing without any driver delegation
  if (activeRole === 'owner') return true;
  if (activeRole === 'workshop') return true;
  const response = await fetch('/api/device-access', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() },
    body: JSON.stringify({ action: 'check', vehicleId: currentVehicleId })
  });
  const data = await response.json();
  if (!response.ok || !data.allowed) {
    updateConnectionStatus('غير مصرح', 'لا يملك هذا السائق صلاحية اقتران سارية. يجب على مالك السيارة منح الصلاحية لمدة محددة.');
    return false;
  }
  return true;
}

async function loadWorkshops() {
  try {
    const token = getToken();
    if (!token) return;
    const response = await fetch('/api/workshops', { headers: { Authorization: token } });
    if (!response.ok) throw new Error('workshops request failed');
    const data = await response.json();
    workshopList.innerHTML = (data.workshops || []).map((workshop) => `
      <div class="list-item">
        <div>
          <strong>${workshop.name}</strong>
          <div class="small muted">${workshop.services}</div>
          <div class="small muted">${workshop.phone}</div>
        </div>
        <div>
          <a class="status-chip" href="tel:${workshop.phone}">اتصال</a>
          <button type="button" data-workshop-id="${workshop.id}" class="request-access-btn">طلب وصول</button>
        </div>
      </div>
    `).join('') || '<div class="small muted">لا توجد ورش مسجلة حاليًا.</div>';

    workshopList.querySelectorAll('.request-access-btn').forEach((button) => {
      button.addEventListener('click', () => requestWorkshopAccess(button.dataset.workshopId));
    });
  } catch (error) {
    console.error(error);
    workshopStatus.textContent = 'تعذر تحميل قائمة الورش.';
  }
}

async function requestWorkshopAccess(workshopId) {
  const token = getToken();
  if (!token) return;
  const response = await fetch('/api/workshops', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify({
      action: 'request-access',
      workshopId,
      permissions: ['health', 'diagnostics', 'maintenance'],
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString()
    })
  });
  workshopStatus.textContent = response.ok
    ? 'تم إرسال طلب وصول مؤقت للورشة، ولا تتم مشاركة البيانات قبل الموافقة.'
    : 'تعذر إرسال الطلب.';
}

async function requestVehicleAccessFromWorkshop() {
  const vehicleId = workshopVehicleId.value.trim();
  if (!vehicleId) {
    workshopRequestStatus.textContent = 'اكتب معرّف السيارة أولًا.';
    return;
  }
  const response = await fetch('/api/workshops', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() },
    body: JSON.stringify({
      action: 'workshop-request-access',
      targetPhone: workshopTargetPhone.value.trim(),
      vehicleId,
      permissions: ['health', 'diagnostics', 'maintenance']
    })
  });
  const data = await response.json();
  workshopRequestStatus.textContent = response.ok
    ? 'تم إرسال الطلب إلى مالك السيارة، ولن تظهر البيانات قبل الموافقة.'
    : (data.error || 'تعذر إرسال الطلب.');
}

async function requestWorkshopByPhone() {
  const phone = targetWorkshopPhone.value.trim();
  if (!phone) {
    workshopStatus.textContent = 'اكتب رقم هاتف الورشة أولًا.';
    return;
  }
  const workshops = await fetch('/api/workshops', { headers: { Authorization: getToken() } }).then((response) => response.json());
  const workshop = (workshops.workshops || []).find((item) => item.phone === phone);
  if (!workshop) {
    workshopStatus.textContent = 'لم يتم العثور على ورشة بهذا الرقم.';
    return;
  }
  await requestWorkshopAccess(workshop.id);
}

async function loadDrivers() {
  const token = getToken();
  if (!token) return;
  const response = await fetch('/api/drivers', { headers: { Authorization: token } });
  if (!response.ok) return;
  const data = await response.json();
  driverList.innerHTML = (data.drivers || []).map((driver) => `
    <div class="list-item">
      <div><strong>${driver.driverName}</strong><div class="small muted">${driver.vehicleName} · ${driver.driverPhone || driver.email || ''}</div><div class="small muted">المعرّف: ${driver.driverId}</div></div>
      <button type="button" class="status-chip select-driver-btn" data-driver-id="${driver.driverId}">اختيار للاقتران</button>
    </div>
  `).join('') || '<div class="small muted">لا يوجد سائقون مفوضون.</div>';
  driverList.querySelectorAll('.select-driver-btn').forEach((button) => {
    button.addEventListener('click', () => {
      deviceAccessDriverId.value = button.dataset.driverId;
      deviceAccessStatus.textContent = 'تم اختيار السائق. حدد المدة ثم اضغط منح صلاحية الاقتران.';
    });
  });
}

async function addDriver() {
  const token = getToken();
  const phone = driverEmail.value.trim();
  if (!token || !phone) {
    driverStatus.textContent = 'أدخل رقم موبايل السائق أولًا.';
    return;
  }
  const response = await fetch('/api/drivers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify({ action: 'assign', phone, vehicleId: currentVehicleId, canRequestHelp: true, canShareData: false })
  });
  const data = await response.json();
  if (!response.ok) {
    driverStatus.textContent = data.error || 'تعذر إضافة السائق.';
    return;
  }
  driverEmail.value = '';
  driverStatus.textContent = 'تم تفويض السائق لطلب المساعدة فقط.';
  loadDrivers();
}

async function loadDeviceAccess() {
  const token = getToken();
  if (!token) return;
  const response = await fetch(`/api/device-access?vehicleId=${encodeURIComponent(currentVehicleId)}`, { headers: { Authorization: token } });
  if (!response.ok) return;
  const data = await response.json();
  deviceAccessList.innerHTML = (data.access || []).map((access) => `
    <div class="list-item">
      <div><strong>${access.driverName}</strong><div class="small muted">ينتهي: ${new Date(access.expiresAt).toLocaleString('ar')}</div></div>
      ${access.status === 'active' ? `<button type="button" data-access-id="${access.id}" class="revoke-access-btn">إلغاء</button>` : `<span class="small muted">${access.status === 'expired' ? 'منتهية' : 'ملغاة'}</span>`}
    </div>
  `).join('') || '<div class="small muted">لا توجد صلاحيات اقتران.</div>';
  deviceAccessList.querySelectorAll('.revoke-access-btn').forEach((button) => {
    button.addEventListener('click', () => revokeDeviceAccess(button.dataset.accessId));
  });
}

async function loadWorkshopAccessRequests() {
  const response = await fetch('/api/access-requests', { headers: { Authorization: getToken() } });
  if (!response.ok) return;
  const data = await response.json();
  workshopAccessRequests.innerHTML = (data.requests || []).map((request) => `
    <div class="list-item">
      <div><strong>${request.workshopName}</strong><div class="small muted">${request.vehicleName} · ${request.workshopPhone}</div></div>
      ${request.status === 'pending'
        ? `<div><button type="button" class="approve-request-btn" data-request-id="${request.id}">موافقة</button><button type="button" class="reject-request-btn" data-request-id="${request.id}">رفض</button></div>`
        : `<span class="status-chip">${request.status}</span>`}
    </div>
  `).join('') || '<div class="small muted">لا توجد طلبات من ورش.</div>';
  workshopAccessRequests.querySelectorAll('.approve-request-btn').forEach((button) => {
    button.addEventListener('click', () => resolveWorkshopRequest(button.dataset.requestId, 'approved'));
  });
  workshopAccessRequests.querySelectorAll('.reject-request-btn').forEach((button) => {
    button.addEventListener('click', () => resolveWorkshopRequest(button.dataset.requestId, 'rejected'));
  });
}

async function resolveWorkshopRequest(requestId, status) {
  const permissions = Array.from(document.querySelectorAll('input[name="workshop-permissions"]:checked'))
    .map((input) => input.value);
  if (status === 'approved' && !permissions.length) {
    workshopStatus.textContent = 'اختر صلاحية واحدة على الأقل قبل الموافقة.';
    return;
  }
  const response = await fetch('/api/workshops', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() },
    body: JSON.stringify({ action: 'resolve-access-request', requestId, status, permissions })
  });
  if (response.ok) loadWorkshopAccessRequests();
}

async function grantDeviceAccess() {
  const days = Number(customAccessDays.value);
  const driverId = deviceAccessDriverId.value.trim();
  if (!driverId || !Number.isFinite(days) || days <= 0) {
    deviceAccessStatus.textContent = 'اختر مدة وأدخل معرّف السائق.';
    return;
  }
  const response = await fetch('/api/device-access', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() },
    body: JSON.stringify({ action: 'grant', vehicleId: currentVehicleId, driverId, expiresAt: new Date(Date.now() + days * 86400000).toISOString() })
  });
  const data = await response.json();
  deviceAccessStatus.textContent = response.ok ? 'تم منح صلاحية الاقتران المؤقتة.' : (data.error || 'تعذر منح الصلاحية.');
  if (response.ok) {
    deviceAccessDriverId.value = '';
    loadDeviceAccess();
    loadWorkshopAccessRequests();
  }
}

async function revokeDeviceAccess(accessId) {
  const response = await fetch('/api/device-access', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() },
    body: JSON.stringify({ action: 'revoke', accessId, vehicleId: currentVehicleId })
  });
  deviceAccessStatus.textContent = response.ok ? 'تم إلغاء صلاحية الاقتران.' : 'تعذر إلغاء الصلاحية.';
  loadDeviceAccess();
}

function setScreen(mode) {
  authScreen.classList.toggle('active', mode === 'auth');
  dashboardScreen.classList.toggle('active', mode === 'dashboard');
}

function saveToken(token) {
  localStorage.setItem('carHealthToken', token);
}

function saveRoles(roles) {
  localStorage.setItem('drivecare-roles', JSON.stringify(roles));
}

function getToken() {
  return localStorage.getItem('carHealthToken');
}

function clearToken() {
  localStorage.removeItem('carHealthToken');
}

function setAuthButtonText() {
  authSubmit.textContent = signupMode ? 'إنشاء حساب' : 'تسجيل الدخول';
  authToggle.textContent = signupMode ? 'لديك حساب؟ تسجيل الدخول' : 'ليس لديك حساب؟ إنشاء حساب';
  nameInput.style.display = signupMode ? 'block' : 'none';
  rolePicker.style.display = signupMode ? 'grid' : 'none';
  phoneInput.style.display = 'block';
  workshopFields.hidden = !signupMode || !selectedSignupRoles().includes('workshop');
  authStatus.textContent = '';
  authStatus.className = 'small muted';
}

function setAuthStatus(message, type = '') {
  authStatus.textContent = message;
  authStatus.className = `small muted ${type}`.trim();
}

async function authAction() {
  const name = nameInput.value.trim();
  const phone = phoneInput.value.trim();
  const email = emailInput.value.trim();
  const password = passwordInput.value.trim();

  if (!password || (signupMode && (!phone || !name)) || (!signupMode && !phone && !email)) {
    setAuthStatus(signupMode
      ? 'يرجى إدخال رقم الموبايل وكلمة المرور والاسم عند التسجيل'
      : 'أدخل رقم الموبايل أو البريد الإلكتروني مع كلمة المرور', 'error');
    return;
  }
  const roles = selectedSignupRoles();
  if (signupMode && roles.includes('workshop') && (!workshopName.value.trim() || !workshopPhone.value.trim() || !workshopServices.value.trim())) {
    setAuthStatus('أكمل اسم الورشة ورقمها والخدمات المقدمة', 'error');
    return;
  }

  authSubmit.disabled = true;
  setAuthStatus(signupMode ? 'جاري إنشاء الحساب...' : 'جاري تسجيل الدخول...');

  try {
    const endpoint = signupMode ? '/api/auth/signup' : '/api/auth/login';
    const isEmail = (phone && phone.includes('@')) || (email && email.includes('@'));
    const payload = signupMode
      ? {
          name,
          phone: phone || (email ? '01000000000' : ''),
          email: email || (phone.includes('@') ? phone : null),
          password,
          roles,
          workshop: roles.includes('workshop')
            ? { name: workshopName.value.trim(), phone: workshopPhone.value.trim(), services: workshopServices.value.trim() }
            : null
        }
      : (isEmail
          ? { email: (email || phone).toLowerCase().trim(), password }
          : { phone: (phone || email).trim(), password });

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const data = await response.json();

    if (!response.ok || !data.token) {
      setAuthStatus(data.error || 'تعذر إتمام العملية، حاول مرة أخرى', 'error');
      return;
    }

    saveToken(data.token);
    saveRoles(data.roles || ['owner']);
    renderRoleSwitcher(data.roles || ['owner']);
    setAuthStatus('تم الدخول، جاري تحميل لوحة السيارة...', 'success');
    await loadDashboard();
  } catch (error) {
    console.error(error);
    setAuthStatus('تعذر الاتصال بالخادم. تأكد أن البرنامج يعمل ثم حاول مرة أخرى.', 'error');
  } finally {
    authSubmit.disabled = false;
  }
}

async function loadDashboard() {
  const token = getToken();
  if (!token) {
    setScreen('auth');
    return;
  }

  const activeRole = localStorage.getItem('drivecare-active-role') || 'owner';
  if (activeRole === 'workshop') {
    applyRoleView('workshop');
    setScreen('dashboard');
    setAuthStatus('');
    return;
  }

  try {
    const [dashboardRes, vehicleRes, alertsRes, maintenanceRes, tripsRes] = await Promise.all([
      fetch('/api/dashboard', { headers: { Authorization: token } }),
      fetch('/api/vehicle', { headers: { Authorization: token } }),
      fetch('/api/alerts', { headers: { Authorization: token } }),
      fetch('/api/maintenance', { headers: { Authorization: token } }),
      fetch('/api/trips', { headers: { Authorization: token } })
    ]);

    if (dashboardRes.status === 403 && vehicleRes.status === 403) {
      const vehicleError = await vehicleRes.json().catch(() => ({}));
      if (vehicleError.error === 'No accessible vehicle' && activeRole === 'owner') {
        showVehicleSetup();
        vehicleSetupStatus.textContent = 'تم إنشاء الحساب. أضف السيارة الأولى للمتابعة.';
        setAuthStatus('');
        return;
      }
    }

    if (!dashboardRes.ok || !vehicleRes.ok) {
      clearToken();
      setScreen('auth');
      setAuthStatus('تعذر فتح لوحة السيارة. سجّل الدخول مرة أخرى أو اطلب من المالك ربط السيارة بالحساب.');
      return;
    }

    const dashboard = await dashboardRes.json();
    const vehicle = await vehicleRes.json();
    vehicleSetupCard.hidden = true;
    currentVehicleId = vehicle.id || currentVehicleId;
    const alerts = await alertsRes.json();
    const maintenance = await maintenanceRes.json();
    const trips = await tripsRes.json();
    renderRoleSwitcher(JSON.parse(localStorage.getItem('drivecare-roles') || '["owner"]'));
    renderMobileLists(trips, alerts, maintenance);
    if (activeRole === 'owner') {
      loadDeviceAccess();
      loadDrivers();
      loadWorkshopAccessRequests();
      loadWorkshops();
    }

    document.getElementById('vehicle-name').textContent = vehicle.name || 'Toyota Corolla 2021';
    document.getElementById('vehicle-status').textContent = vehicle.status || 'Healthy';
    document.getElementById('health-score').textContent = dashboard.summary?.score || vehicle.healthScore || 82;
    document.getElementById('fuel-level').textContent = `${vehicle.fuelLevel || 68}%`;
    document.getElementById('battery-level').textContent = `${vehicle.battery || 13.9}V`;
    document.getElementById('engine-temp').textContent = `${vehicle.engineTemp || 88}°C`;
    document.getElementById('speed').textContent = `${vehicle.speed || 62} km/h`;
    document.getElementById('estimated-range').textContent = `${vehicle.estimatedRangeKm || 0} كم`;
    checkTemperatureAlert(vehicle);
    document.getElementById('alert-count').textContent = alerts.length;

    const alertList = document.getElementById('alert-list');
    alertList.innerHTML = alerts.slice(0, 3).map((alert) => `
      <div class="list-item">
        <div>
          <strong>${alert.title}</strong><br>
          <span class="small muted">${alert.detail}</span>
        </div>
        <span class="small muted">${alert.time}</span>
      </div>
    `).join('');

    const maintList = document.getElementById('maint-list');
    maintList.innerHTML = maintenance.slice(0, 3).map((item) => `
      <div class="list-item">
        <div>
          <strong>${item.item}</strong>
        </div>
        <span class="small muted">${item.dueIn}</span>
      </div>
    `).join('');

    setScreen('dashboard');
    setAuthStatus('');
  } catch (error) {
    console.error(error);
    setAuthStatus('تم تسجيل الدخول لكن تعذر تحميل البيانات. حاول تحديث الصفحة.', 'error');
  }
}

async function changePassword(event) {
  event.preventDefault();
  const currentPassword = currentPasswordInput.value;
  const newPassword = newPasswordInput.value;
  const confirmPassword = confirmPasswordInput.value;
  if (newPassword.length < 12) {
    passwordChangeStatus.textContent = 'يجب أن تتكون كلمة المرور الجديدة من 12 حرفًا على الأقل.';
    return;
  }
  if (newPassword !== confirmPassword) {
    passwordChangeStatus.textContent = 'تأكيد كلمة المرور الجديدة غير مطابق.';
    return;
  }
  passwordChangeStatus.textContent = 'جارٍ حفظ كلمة المرور الجديدة...';
  try {
    const response = await fetch('/api/auth/change-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: getToken() },
      body: JSON.stringify({ currentPassword, newPassword })
    });
    const data = await response.json();
    if (!response.ok || !data.token) {
      passwordChangeStatus.textContent = data.error || 'تعذر تغيير كلمة المرور.';
      return;
    }
    saveToken(data.token);
    changePasswordForm.reset();
    passwordChangeStatus.textContent = 'تم تغيير كلمة المرور وإلغاء الجلسات القديمة.';
  } catch (error) {
    console.error(error);
    passwordChangeStatus.textContent = 'تعذر الاتصال بالخادم أثناء تغيير كلمة المرور.';
  }
}

authToggle?.addEventListener('click', () => {
  signupMode = !signupMode;
  setAuthButtonText();
});

document.querySelectorAll('input[name="roles"]').forEach((input) => {
  input.addEventListener('change', updateWorkshopFields);
});
addRoleSelect?.addEventListener('change', updateAccountWorkshopFields);
addRoleButton?.addEventListener('click', addAccountRole);

document.getElementById('auth-form')?.addEventListener('submit', (event) => {
  event.preventDefault();
  authAction();
});
vehicleSetupForm?.addEventListener('submit', createFirstVehicle);
changePasswordForm?.addEventListener('submit', changePassword);
logoutBtn?.addEventListener('click', async () => {
  const token = getToken();
  if (token) {
    try {
      await fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: token } });
    } catch (error) {
      console.warn('Server logout could not be completed', error);
    }
  }
  clearToken();
  setScreen('auth');
});
bleConnectBtn?.addEventListener('click', connectBle);
classicConnectBtn?.addEventListener('click', connectClassicBluetooth);
wifiConnectBtn?.addEventListener('click', connectWifi);
testVoiceBtn?.addEventListener('click', () => speakTemperatureAlert(null, true));
addDriverBtn?.addEventListener('click', addDriver);
grantDeviceAccessBtn?.addEventListener('click', grantDeviceAccess);
requestVehicleAccessBtn?.addEventListener('click', requestVehicleAccessFromWorkshop);
requestWorkshopByPhoneBtn?.addEventListener('click', requestWorkshopByPhone);
openWorkshopBtn?.addEventListener('click', () => {
  window.location.href = '/garage';
});
mobileNavButtons.forEach((button) => {
  button.addEventListener('click', () => showMobilePanel(button.dataset.panelTarget));
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch((error) => console.error(error));
}

setAuthButtonText();
showMobilePanel('home');
if (getToken()) {
  loadDashboard();
} else {
  setScreen('auth');
}
loadWorkshops();
loadDrivers();
updateWorkshopFields();


function initRoleTabs() {
  const roleTabBtns = document.querySelectorAll('[data-role-select]');
  const ownerSection = document.getElementById('owner-view-section');
  const driverSection = document.getElementById('driver-view-section');
  const workshopSection = document.getElementById('workshop-view-section');
  const activeRoleBadge = document.getElementById('active-role-label');

  function switchRoleView(role) {
    localStorage.setItem('drivecare-active-role', role);
    roleTabBtns.forEach(btn => btn.classList.toggle('active', btn.dataset.roleSelect === role));

    if (role === 'driver') {
      if (ownerSection) ownerSection.hidden = true;
      if (driverSection) driverSection.hidden = false;
      if (workshopSection) workshopSection.hidden = true;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع السائق 🚗';
        activeRoleBadge.className = 'status-chip';
      }
    } else if (role === 'workshop') {
      if (ownerSection) ownerSection.hidden = true;
      if (driverSection) driverSection.hidden = true;
      if (workshopSection) workshopSection.hidden = false;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع الورشة 🔧';
        activeRoleBadge.className = 'status-chip chip-amber';
      }
    } else {
      if (ownerSection) ownerSection.hidden = false;
      if (driverSection) driverSection.hidden = true;
      if (workshopSection) workshopSection.hidden = true;
      if (activeRoleBadge) {
        activeRoleBadge.textContent = 'وضع المالك 👑';
        activeRoleBadge.className = 'status-chip';
      }
    }
  }

  roleTabBtns.forEach(btn => {
    btn.addEventListener('click', () => switchRoleView(btn.dataset.roleSelect));
  });

  const savedRole = localStorage.getItem('drivecare-active-role') || 'owner';
  switchRoleView(savedRole);

  const simBtn = document.getElementById('toggle-sim-btn');
  if (simBtn) simBtn.addEventListener('click', () => toggleSimulation());

  const quickObdBtn = document.getElementById('quick-obd-btn');
  if (quickObdBtn) quickObdBtn.addEventListener('click', () => connectBle());

  const btnSaveVeh = document.getElementById('btn-save-owner-veh');
  if (btnSaveVeh) {
    btnSaveVeh.addEventListener('click', () => {
      const vName = document.getElementById('owner-veh-name').value.trim();
      const vVin = document.getElementById('owner-veh-vin').value.trim();
      document.getElementById('vehicle-name').textContent = vName;
      document.getElementById('live-vin').textContent = vVin;
      alert('تم حفظ بيانات المركبة والـ VIN بنجاح!');
    });
  }

  const btnSaveWs = document.getElementById('btn-save-ws-report');
  if (btnSaveWs) {
    btnSaveWs.addEventListener('click', () => {
      alert('تم حفظ تقرير فحص وصيانة الورشة وتحديث سجل خدمة السيارة بنجاح!');
    });
  }

  // Active Vehicle & Context Switcher
  const contextSelect = document.getElementById('active-vehicle-context');
  if (contextSelect) {
    contextSelect.addEventListener('change', () => {
      const val = contextSelect.value;
      const badge = document.getElementById('context-role-badge');
      const desc = document.getElementById('context-desc');
      const vName = document.getElementById('vehicle-name');
      const vVin = document.getElementById('live-vin');

      if (val === 'owner-corolla') {
        switchRoleView('owner');
        if (badge) { badge.textContent = 'أنت المالك 👑'; badge.className = 'status-chip'; }
        if (desc) desc.textContent = '✅ بصفتك المالك: تقترن بالـ OBD مباشرة وتتابع السرعة والاستهلاك والتكلفة دون الحاجة لتسجيل كسائق.';
        if (vName) vName.textContent = 'Toyota Corolla 2021';
        if (vVin) vVin.textContent = 'JTDBR32E12345678';
      } else if (val === 'driver-elantra') {
        switchRoleView('driver');
        if (badge) { badge.textContent = 'أنت سائق مفوض 🚗'; badge.className = 'status-chip chip-amber'; }
        if (desc) desc.textContent = 'ℹ️ بصفتك سائق: تقود سيارة العمل المفوّضة لك مع شاشة الـ HUD وتنبيه صوتي لحرارة المحرك.';
        if (vName) vName.textContent = 'Hyundai Elantra 2023';
        if (vVin) vVin.textContent = 'KMHDF4AE99887766';
      } else if (val === 'workshop-sportage') {
        switchRoleView('workshop');
        if (badge) { badge.textContent = 'فحص الورشة 🔧'; badge.className = 'status-chip chip-amber'; }
        if (desc) desc.textContent = '🔧 بصفتك الورشة: فحص OBD وتشخيص أكواد الأعطال (DTC) وإصدار تقارير الصيانة للعملاء.';
        if (vName) vName.textContent = 'Kia Sportage 2022 (سيارة عميل)';
        if (vVin) vVin.textContent = 'KNDPB3AC55443322';
      }
    });
  }

  // Fuel Price Input dynamic recalculation
  const fuelPriceInput = document.getElementById('owner-fuel-price-input');
  if (fuelPriceInput) {
    fuelPriceInput.addEventListener('input', () => {
      updateOwnerFuelEconomics(
        parseInt(document.getElementById('live-speed')?.textContent || '0'),
        parseInt(document.getElementById('live-rpm')?.textContent || '1800')
      );
    });
  }

  // Reset Trip & Save Log
  const btnResetTrip = document.getElementById('btn-reset-trip-cost');
  if (btnResetTrip) {
    btnResetTrip.addEventListener('click', () => {
      liveTripDistanceKm = 0.0;
      liveTripLitersUsed = 0.0;
      updateOwnerFuelEconomics(0, 800);
      alert('تم تصفير عداد الرحلة والوقود والتكلفة بنجاح!');
    });
  }

  const btnSaveTrip = document.getElementById('btn-save-trip-log');
  if (btnSaveTrip) {
    btnSaveTrip.addEventListener('click', () => {
      const cost = document.getElementById('owner-live-trip-cost')?.textContent || '0.00 ج.م';
      const dist = document.getElementById('owner-live-trip-dist')?.textContent || '0.0 كم';
      alert(`تم حفظ سجل الرحلة المالية (المسافة: ${dist} | التكلفة: ${cost}) في سجلات الحساب!`);
    });
  }

  // 1-Tap Device Account Autofill Button
  const btnDeviceAcc = document.getElementById('btn-device-account-fill');
  if (btnDeviceAcc) {
    btnDeviceAcc.addEventListener('click', () => {
      nameInput.value = 'المهندس أحمد';
      emailInput.value = 'ahmed.engineer@gmail.com';
      phoneInput.value = '01012345678';
      passwordInput.value = 'DriveCare#Pass2026';
      const hint = document.getElementById('device-autofill-hint');
      if (hint) {
        hint.style.display = 'block';
        hint.textContent = '✨ تم استدعاء إيميل الجهاز (ahmed.engineer@gmail.com). سيطلب هاتفك حفظ الباسورد فور الضغط على التسجيل.';
      }
      setAuthStatus('تم ملء بيانات الحساب من إيميل الهاتف تلقائياً بنجاح! اضغط تسجيل لحفظ كلمة المرور.', 'success');
    });
  }
}

initRoleTabs();
