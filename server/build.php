<?php
require __DIR__ . '/lib/auth.php';
$user = require_login();
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Website to App Converter</title>
<style>
  :root{
    --bg:#0f1115; --card:#171a21; --accent:#3ddc84; --accent2:#2bb673;
    --text:#e8eaed; --muted:#9aa0a6; --border:#262b33;
  }
  *{box-sizing:border-box;}
  body{
    margin:0; font-family:'Segoe UI',Roboto,Arial,sans-serif;
    background:linear-gradient(160deg,#0f1115,#141821); color:var(--text);
    min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px;
  }
  .card{
    background:var(--card); border:1px solid var(--border); border-radius:16px;
    padding:32px; width:100%; max-width:560px; box-shadow:0 20px 60px rgba(0,0,0,.4);
  }
  .topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;font-size:12px;color:var(--muted);}
  .topbar a{color:var(--muted);text-decoration:none;}
  .topbar a.accent{color:var(--accent);font-weight:600;}
  h1{font-size:22px; margin:0 0 4px;}
  p.sub{color:var(--muted); margin:0 0 20px; font-size:14px;}

  .section{border-top:1px solid var(--border); padding-top:18px; margin-top:18px;}
  .section:first-of-type{border-top:none; padding-top:0; margin-top:0;}
  .section-title{font-size:13px; font-weight:700; text-transform:uppercase; letter-spacing:.04em; color:var(--accent); margin-bottom:12px;}

  label{display:block; font-size:13px; color:var(--muted); margin:14px 0 6px;}
  label:first-child{margin-top:0;}
  input[type=text], input[type=url]{
    width:100%; padding:12px 14px; border-radius:10px; border:1px solid var(--border);
    background:#0d0f13; color:var(--text); font-size:14px; outline:none;
  }
  input:focus{border-color:var(--accent);}

  .icon-drop{
    border:2px dashed var(--border); border-radius:12px; padding:16px; text-align:center;
    cursor:pointer; margin-top:6px; position:relative;
  }
  .icon-drop img{width:56px;height:56px;border-radius:14px;object-fit:cover;}
  .icon-drop input{position:absolute; inset:0; opacity:0; cursor:pointer;}

  .row{display:flex; gap:12px;}
  .row > div{flex:1;}
  .color-row{display:flex; align-items:center; gap:10px;}
  input[type=color]{width:44px; height:38px; padding:2px; border-radius:8px; border:1px solid var(--border); background:#0d0f13;}
  .swatch-name{font-size:13px; color:var(--muted);}

  .toggle-row{display:flex; align-items:center; justify-content:space-between; padding:10px 0;}
  .toggle-row .label-text{font-size:14px;}
  .toggle-row .desc{font-size:12px; color:var(--muted); margin-top:2px;}
  .switch{position:relative; width:42px; height:24px; flex-shrink:0;}
  .switch input{opacity:0; width:0; height:0;}
  .slider{position:absolute; inset:0; background:#2a2f3a; border-radius:24px; cursor:pointer; transition:.2s;}
  .slider:before{content:""; position:absolute; width:18px; height:18px; left:3px; top:3px; background:white; border-radius:50%; transition:.2s;}
  input:checked + .slider{background:var(--accent);}
  input:checked + .slider:before{transform:translateX(18px);}

  #navTabsList{margin-top:6px;}
  .nav-tab-row{display:flex; gap:8px; margin-bottom:8px; align-items:center;}
  .nav-tab-row input{flex:1;}
  .nav-tab-row input.icon-input{flex:0 0 50px; text-align:center;}
  .nav-tab-row button{flex:0 0 auto; margin:0; padding:8px 12px; width:auto; background:#2a2f3a; color:var(--muted); font-weight:400; font-size:16px; border:none; border-radius:8px; cursor:pointer;}
  #addTabBtn{margin-top:0; background:#1b2029; color:var(--accent); border:1px dashed var(--border); font-size:13px; padding:10px; width:100%; border-radius:10px; cursor:pointer;}

  button.primary{
    margin-top:26px; width:100%; padding:14px; border:none; border-radius:10px;
    background:linear-gradient(90deg,var(--accent),var(--accent2)); color:#04150a;
    font-weight:700; font-size:15px; cursor:pointer;
  }
  button.primary:disabled{opacity:.6; cursor:not-allowed;}

  #status{margin-top:16px; font-size:13px; color:var(--muted); white-space:pre-wrap;}
  #dlList{margin-top:16px; display:flex; flex-direction:column; gap:8px;}
  .dl-row{display:flex; justify-content:space-between; align-items:center; gap:10px; background:#0d0f13; border:1px solid var(--border); border-radius:10px; padding:10px 14px;}
  .dl-row .plat{font-size:13px; font-weight:600;}
  .dl-row .sub{font-size:12px; color:var(--muted);}
  .dl-row a.dl{background:var(--accent); color:#04150a; padding:8px 14px; border-radius:8px; text-decoration:none; font-weight:700; font-size:12px; white-space:nowrap;}
  .dl-row .pending{font-size:12px; color:var(--muted); white-space:nowrap;}
  .dl-row .failed{font-size:12px; color:#ff8a8a; white-space:nowrap;}
  .req{font-size:12px;color:var(--muted);margin-top:22px;line-height:1.5;border-top:1px solid var(--border);padding-top:14px;}
  .spinner{display:inline-block;width:14px;height:14px;border:2px solid var(--muted);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;margin-right:8px;vertical-align:-2px;}
  @keyframes spin{to{transform:rotate(360deg);}}
</style>
</head>
<body>
<div class="card">
  <div class="topbar">
    <span>Signed in as <strong><?= htmlspecialchars($user['username']) ?></strong> (<?= htmlspecialchars($user['role']) ?>)</span>
    <span>
      <a href="my-apps.php">My Apps</a>
      <?php if ($user['role'] === 'admin'): ?> &nbsp;|&nbsp; <a href="admin.php" class="accent">Admin</a><?php endif; ?>
      &nbsp;|&nbsp; <a href="logout.php">Log out</a>
    </span>
  </div>

  <h1>🌐 → 📦 Website to App</h1>
  <p class="sub">Build a real, customized Android, Windows, or macOS app from your website in a couple minutes.</p>

  <form id="apkForm">

    <div class="section">
      <div class="section-title">Basics</div>
      <label>App name</label>
      <input type="text" name="app_name" id="app_name" placeholder="My Cool App" required>

      <label>Website URL (your domain)</label>
      <input type="url" name="app_url" id="app_url" placeholder="https://example.com" required>

      <label>App icon (square PNG/JPG, ideally 512x512)</label>
      <div class="icon-drop" id="iconDrop">
        <span id="iconPlaceholder">Tap to choose an image</span>
        <img id="iconPreview" style="display:none;">
        <input type="file" name="icon" id="icon" accept="image/png,image/jpeg" required>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Platforms to build</div>
      <div class="toggle-row">
        <div>
          <div class="label-text">🤖 Android (.apk)</div>
          <div class="desc">Installs directly on Android phones/tablets</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="platform_android" id="platform_android" checked>
          <span class="slider"></span>
        </label>
      </div>
      <div class="toggle-row">
        <div>
          <div class="label-text">🪟 Windows (.exe)</div>
          <div class="desc">Installer for Windows 10/11 desktops</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="platform_windows" id="platform_windows">
          <span class="slider"></span>
        </label>
      </div>
      <div class="toggle-row">
        <div>
          <div class="label-text">🍎 macOS (.dmg)</div>
          <div class="desc">For Intel and Apple Silicon Macs</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="platform_macos" id="platform_macos">
          <span class="slider"></span>
        </label>
      </div>
      <div class="desc" style="margin-top:8px;">
        Windows/macOS builds aren't code-signed (no paid developer certificate is configured),
        so Windows shows a one-time SmartScreen warning ("More info" → "Run anyway"), and on
        Mac you right-click the app → "Open" the first time instead of double-clicking.
      </div>
    </div>

    <div class="section">
      <div class="section-title">Appearance</div>
      <div class="row">
        <div>
          <label>Primary color</label>
          <div class="color-row">
            <input type="color" name="primary_color" id="primary_color" value="#3DDC84">
            <span class="swatch-name">splash + progress bar</span>
          </div>
        </div>
        <div>
          <label>Accent color</label>
          <div class="color-row">
            <input type="color" name="accent_color" id="accent_color" value="#3DDC84">
            <span class="swatch-name">progress bar tint</span>
          </div>
        </div>
      </div>

      <div class="toggle-row">
        <div>
          <div class="label-text">Splash screen</div>
          <div class="desc">Show a branded launch screen before the app opens</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="splash_enabled" id="splash_enabled" checked>
          <span class="slider"></span>
        </label>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Bottom navigation (optional, up to 5 tabs)</div>
      <div id="navTabsList"></div>
      <button type="button" id="addTabBtn">+ Add tab</button>
    </div>

    <div class="section">
      <div class="section-title">Features</div>
      <div class="toggle-row">
        <div>
          <div class="label-text">Push notifications</div>
          <div class="desc">Lets you send announcements to installed apps later</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="push_enabled" id="push_enabled">
          <span class="slider"></span>
        </label>
      </div>
      <div class="toggle-row">
        <div>
          <div class="label-text">File upload / camera / mic</div>
          <div class="desc">Needed for forms with file inputs, or camera/mic on your site</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="filecamera_enabled" id="filecamera_enabled" checked>
          <span class="slider"></span>
        </label>
      </div>
    </div>

    <div class="section">
      <div class="section-title">Advanced (optional)</div>
      <label>Crash report URL</label>
      <input type="url" name="crash_report_url" id="crash_report_url" placeholder="https://yoursite.com/crash_report.php">
      <div class="desc" style="margin:-6px 0 12px;">If your site has an endpoint to receive them, the app will silently POST a small JSON report there after any crash. Leave blank to disable.</div>

      <label>Privacy policy URL</label>
      <input type="url" name="privacy_policy_url" id="privacy_policy_url" placeholder="https://yoursite.com/privacy">
      <div class="desc" style="margin:-6px 0 16px;">Shown once as a link on first launch, if set. Leave blank to skip.</div>

      <div class="toggle-row">
        <div>
          <div class="label-text">Kiosk mode (Android, Windows &amp; macOS)</div>
          <div class="desc">Pins the app fullscreen - for POS terminals, check-in kiosks, single-purpose tablets. On Android people can still exit via the back+recents hold gesture; on Windows/macOS it can be switched off in the app's Settings (Ctrl/Cmd + ,). If unchecked, the desktop app has no kiosk option at all.</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="kiosk_enabled" id="kiosk_enabled">
          <span class="slider"></span>
        </label>
      </div>

      <div class="toggle-row">
        <div>
          <div class="label-text">Require app lock (Windows &amp; macOS)</div>
          <div class="desc">The app opens to a "set a PIN" screen the first time it runs, and can't be used without one. Once set, app lock <strong>cannot be turned off</strong> from the app's own Settings - use this for shared or public computers. If unchecked, the desktop app has no app lock feature at all.</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="applock_enabled" id="applock_enabled">
          <span class="slider"></span>
        </label>
      </div>

      <div class="toggle-row">
        <div>
          <div class="label-text">Keep people logged in (Windows &amp; macOS)</div>
          <div class="desc">Remembers a saved login on this computer and signs back in automatically. Turn this off for shared or public computers so the next person isn't logged in as someone else; people can always sign out as usual, and can clear a saved login from the app's own Settings.</div>
        </div>
        <label class="switch">
          <input type="checkbox" name="remember_login_enabled" id="remember_login_enabled" checked>
          <span class="slider"></span>
        </label>
      </div>
    </div>

    <button type="submit" class="primary" id="submitBtn">Build App</button>
  </form>

  <div id="status"></div>
  <div id="dlList"></div>

  <div class="req">
    This page uploads your icon and starts real, automated builds - genuine
    compiled apps, not faked. Android gets a centered loading spinner and an
    offline fallback screen automatically; Windows/macOS get
    an offline fallback screen and open external links in your normal browser.
    Each platform you selected builds independently, so one finishing (or failing)
    doesn't hold up the others.
  </div>
</div>

<script>
const iconInput = document.getElementById('icon');
const iconPreview = document.getElementById('iconPreview');
const iconPlaceholder = document.getElementById('iconPlaceholder');
iconInput.addEventListener('change', () => {
  const f = iconInput.files[0];
  if(!f) return;
  const reader = new FileReader();
  reader.onload = e => {
    iconPreview.src = e.target.result;
    iconPreview.style.display = 'inline-block';
    iconPlaceholder.style.display = 'none';
  };
  reader.readAsDataURL(f);
});

// --- bottom nav tab builder ---
const navTabsList = document.getElementById('navTabsList');
const addTabBtn = document.getElementById('addTabBtn');
let tabCount = 0;

function addTabRow() {
  if (tabCount >= 5) return;
  tabCount++;
  const row = document.createElement('div');
  row.className = 'nav-tab-row';
  row.innerHTML =
    '<input type="text" class="icon-input nav-icon" placeholder="🏠" maxlength="8">' +
    '<input type="text" class="nav-label" placeholder="Label" maxlength="20">' +
    '<input type="url" class="nav-url" placeholder="https://example.com/page">' +
    '<button type="button" title="Remove">✕</button>';
  row.querySelector('button').addEventListener('click', () => { row.remove(); tabCount--; toggleAddBtn(); });
  navTabsList.appendChild(row);
  toggleAddBtn();
}

function toggleAddBtn() {
  addTabBtn.style.display = tabCount >= 5 ? 'none' : 'block';
}

addTabBtn.addEventListener('click', addTabRow);

function collectNavItems() {
  const rows = navTabsList.querySelectorAll('.nav-tab-row');
  const items = [];
  rows.forEach(row => {
    const icon = row.querySelector('.nav-icon').value.trim();
    const label = row.querySelector('.nav-label').value.trim();
    const url = row.querySelector('.nav-url').value.trim();
    if (label && url) items.push({ icon: icon || '\u25CF', label, url });
  });
  return items;
}

const PLATFORM_LABELS = { android: '🤖 Android', windows: '🪟 Windows', macos: '🍎 macOS' };

function makeRow(platform) {
  const row = document.createElement('div');
  row.className = 'dl-row';
  row.id = 'row-' + platform;
  row.innerHTML =
    '<div><div class="plat">' + PLATFORM_LABELS[platform] + '</div><div class="sub" id="sub-' + platform + '"></div></div>' +
    '<span id="action-' + platform + '"><span class="pending"><span class="spinner"></span>Starting…</span></span>';
  return row;
}

function poll(platform, requestId) {
  const action = document.getElementById('action-' + platform);
  let attempts = 0;

  const timer = setInterval(async () => {
    attempts++;
    if (attempts > 90) {
      clearInterval(timer);
      action.innerHTML = '<span class="failed">Timed out - check My Apps later</span>';
      return;
    }
    try {
      const res = await fetch('status.php?request_id=' + encodeURIComponent(requestId));
      const data = await res.json();
      if (data.ready) {
        clearInterval(timer);
        action.innerHTML = '<a class="dl" href="' + data.download_url + '">⬇ Download</a>';
      } else {
        action.innerHTML = '<span class="pending"><span class="spinner"></span>Building… (' + attempts * 5 + 's)</span>';
      }
    } catch (e) {
      action.innerHTML = '<span class="pending"><span class="spinner"></span>Checking status, retrying…</span>';
    }
  }, 5000);
}

document.getElementById('apkForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = document.getElementById('submitBtn');
  const status = document.getElementById('status');
  const dlList = document.getElementById('dlList');
  dlList.innerHTML = '';

  const platforms = [];
  if (document.getElementById('platform_android').checked) platforms.push('android');
  if (document.getElementById('platform_windows').checked) platforms.push('windows');
  if (document.getElementById('platform_macos').checked) platforms.push('macos');

  if (platforms.length === 0) {
    status.textContent = '❌ Select at least one platform to build for.';
    return;
  }

  btn.disabled = true;
  status.innerHTML = '<span class="spinner"></span> Uploading icon and starting build(s)...';

  const fd = new FormData(e.target);
  fd.set('splash_enabled', document.getElementById('splash_enabled').checked ? '1' : '0');
  fd.set('push_enabled', document.getElementById('push_enabled').checked ? '1' : '0');
  fd.set('filecamera_enabled', document.getElementById('filecamera_enabled').checked ? '1' : '0');
  fd.set('kiosk_enabled', document.getElementById('kiosk_enabled').checked ? '1' : '0');
  fd.set('applock_enabled', document.getElementById('applock_enabled').checked ? '1' : '0');
  fd.set('remember_login_enabled', document.getElementById('remember_login_enabled').checked ? '1' : '0');
  fd.set('nav_items', JSON.stringify(collectNavItems()));
  fd.set('platforms', JSON.stringify(platforms));

  try {
    const res = await fetch('trigger.php', { method: 'POST', body: fd });
    const data = await res.json();

    if (!data.success && !data.results) {
      status.textContent = '❌ ' + (data.error || 'Could not start build.');
      btn.disabled = false;
      return;
    }

    status.textContent = data.results.some(r => r.success)
      ? '✅ Build(s) started - tracking progress below.'
      : '❌ None of the selected builds could be started (see below).';

    data.results.forEach(r => {
      const row = makeRow(r.platform);
      dlList.appendChild(row);
      if (r.success) {
        poll(r.platform, r.request_id);
      } else {
        document.getElementById('action-' + r.platform).innerHTML = '<span class="failed">' + (r.error || 'Failed to start') + '</span>';
      }
    });
  } catch (err) {
    status.textContent = '❌ Network/server error: ' + err.message;
  }
  btn.disabled = false;
});
</script>
</body>
</html>
