
(function() {
    'use strict';

    function toast(msg) { hcj.notify((hcj.manifest && hcj.manifest.name) || 'plugin', String(msg)); }
    function pushPanel() { hcj.panel.send({ type: 'panel', html: getPanelHtml() }); }
    function pushHtml(html) { hcj.panel.send({ type: 'panel', html: html }); }


    // 多语言支持
    const LANG = (hcj.lang || 'zh').toLowerCase().startsWith('ar') ? 'ar' :
                 (hcj.lang || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en';
    const I18N = {
        zh: { name: '隐私保护', subtitle: '保护您的隐私安全', tracking: '阻止追踪', trackingDesc: '拦截常见追踪脚本', fingerprint: '指纹保护', fingerprintDesc: '模糊设备指纹信息', cookies: '清理Cookies', cookiesDesc: '退出时清理Cookies', enabled: '已开启', disabled: '已关闭' },
        en: { name: 'Privacy Protection', subtitle: 'Protect your privacy', tracking: 'Block Tracking', trackingDesc: 'Block common tracking scripts', fingerprint: 'Fingerprint Protection', fingerprintDesc: 'Blur device fingerprint info', cookies: 'Clear Cookies', cookiesDesc: 'Clear cookies on exit', enabled: 'Enabled', disabled: 'Disabled' },
        ar: { name: 'حماية الخصوصية', subtitle: 'حماية خصوصيتك', tracking: 'حظر التتبع', trackingDesc: 'حظر سكريبتات التتبع', fingerprint: 'حماية البصمة', fingerprintDesc: 'تمويه بصمة الجهاز', cookies: 'مسح Cookies', cookiesDesc: 'مسح Cookies عند الخروج', enabled: 'مفعل', disabled: 'موقف' }
    };
    const T = I18N[LANG] || I18N.en;
    const STORAGE_KEY = 'wta_privacy';
    let settings = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{"tracking":true,"fingerprint":true,"cookies":false}');

    function save() { localStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); }

    function applyProtection() {
        if (settings.tracking) {
            const blocked = ['google-analytics.com', 'googletagmanager.com', 'facebook.net', 'doubleclick.net', 'hotjar.com'];
            const origFetch = window.fetch;
            window.fetch = function(url, opts) {
                if (blocked.some(b => url.toString().includes(b))) { console.log('[Privacy] Blocked:', url); return Promise.reject(); }
                return origFetch.apply(this, arguments);
            };
        }
        if (settings.fingerprint) {
            Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 4 });
            Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
            Object.defineProperty(screen, 'colorDepth', { get: () => 24 });
        }
    }

    function updatePanelUI() {
        hcj.panel.send({ type: 'state', settings: settings, labels: { subtitle: T.subtitle, tracking: T.tracking, trackingDesc: T.trackingDesc, fingerprint: T.fingerprint, fingerprintDesc: T.fingerprintDesc, cookies: T.cookies, cookiesDesc: T.cookiesDesc } });
    }

    function privacyToggle(key) {
        settings[key] = !settings[key];
        save();
        toast(settings[key] ? T.enabled : T.disabled);
        updatePanelUI();
    }

    
    hcj.panel.onMessage(function (m) {
        if (!m) return;
        if (m.cmd === 'refresh') { updatePanelUI(); return; }
        if (m.cmd === 'act' && m.action === 'privacyToggle') privacyToggle(m.arg);
    });
    hcj.on('action', function () { hcj.panel.open(); });

    applyProtection();
})();
