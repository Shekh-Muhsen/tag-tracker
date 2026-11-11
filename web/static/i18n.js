// Shared web/Android WebView UI localization. English remains the fallback.
(() => {
  'use strict';
  const messages = {
  "en": {
    "language": "Language",
    "tag": "Tag",
    "history": "History",
    "today": "Today",
    "two_days": "2 days",
    "seven_days": "7 days",
    "thirty_days": "30 days",
    "ninety_days": "90 days",
    "year": "1 year",
    "custom": "Custom",
    "from": "From",
    "to": "To",
    "show": "Show",
    "playback": "Playback",
    "show_points": "Show individual points",
    "auto_refresh": "Auto-refresh every minute",
    "check_now": "Check now",
    "rename": "Rename tag",
    "server": "Change server",
    "logout": "Sign out",
    "settings": "Settings",
    "park": "🅿️ Park & guard",
    "locate": "📍 Locate now",
    "ring": "🔔 Ring",
    "share": "↗ Share",
    "play": "Play",
    "hide_panel": "Hide panel",
    "unlock_pro": "Unlock Pro",
    "drive_sync": "Google Drive sync",
    "recenter": "Center on tag",
    "username": "Username",
    "password": "Password",
    "login": "Sign in",
    "login_sub": "Sign in to see your tag's travel history.",
    "disclaimer": "Not affiliated with Google. Independent, open-source. For tracking your own property.",
    "login_failed": "Sign in failed",
    "no_tags": "No tags yet – waiting for first check",
    "poll_off": "Automatic checking is off on this server.",
    "poll_check": "Checking Google for new locations…",
    "poll_fail": "Last check failed: {error}",
    "poll_last": "Last checked {when} · every {minutes} min",
    "starting": "Starting…",
    "just_now": "just now",
    "mins_ago": "{n} min ago",
    "hours_ago": "{n} h ago",
    "days_ago": "{n} days ago",
    "free_upgrade": "🔒 Free — Upgrade",
    "parked": "🔒 Parked & guarded — tap when you ride",
    "drive_off": "Drive off",
    "drive_off_tip": "Google Drive sync is off. Turn it on in setup.",
    "sync_pending": "Saved · sync pending",
    "sync_pending_tip": "Your locations ARE saved on the phone. Drive sync will retry automatically. ({error})",
    "synced": "Synced",
    "backup_when": "Backed up to Google Drive {when}",
    "drive_ready": "Drive ready",
    "drive_ready_tip": "Google Drive is set up. It uploads once there are locations to back up.",
    "no_tag": "No tag selected.",
    "no_locations": "No locations stored yet.",
    "last_seen": "Last seen at {when}",
    "finding_address": "📍 finding address…",
    "locations_total": "{n} locations stored in total",
    "directions": "🧭 Get directions",
    "latest": "latest",
    "open_maps": "Open in Maps",
    "route": "Directions",
    "own_report": "Reported by your own phone",
    "network_report": "Reported by Find Hub network",
    "locations": "locations",
    "distance": "km travelled",
    "period": "time span",
    "bad_dates": "Pick a valid date range.",
    "locating": "📍 Locating…",
    "asking": "Asking the tag for its current location…",
    "guarding": "Guarding: you’ll be alerted if the tag moves.",
    "riding": "Riding mode: movement alerts are off.",
    "ringing": "Ringing the tag…",
    "no_share": "No location yet to share.",
    "last_seen_short": "last seen",
    "copied": "Location copied to clipboard",
    "copy": "Copy:",
    "new_name": "New name for this tag"
  },
  "pt-BR": {
    "language": "Idioma",
    "tag": "Tag",
    "history": "Histórico",
    "today": "Hoje",
    "two_days": "2 dias",
    "seven_days": "7 dias",
    "thirty_days": "30 dias",
    "ninety_days": "90 dias",
    "year": "1 ano",
    "custom": "Personalizado",
    "from": "De",
    "to": "Até",
    "show": "Exibir",
    "playback": "Reprodução",
    "show_points": "Exibir pontos individuais",
    "auto_refresh": "Atualizar automaticamente a cada minuto",
    "check_now": "Verificar agora",
    "rename": "Renomear tag",
    "server": "Alterar servidor",
    "logout": "Sair",
    "settings": "Configurações",
    "park": "🅿️ Estacionar e proteger",
    "locate": "📍 Localizar agora",
    "ring": "🔔 Tocar som",
    "share": "↗ Compartilhar",
    "play": "Reproduzir",
    "hide_panel": "Ocultar painel",
    "unlock_pro": "Desbloquear Pro",
    "drive_sync": "Sincronização do Google Drive",
    "recenter": "Centralizar na tag",
    "username": "Usuário",
    "password": "Senha",
    "login": "Entrar",
    "login_sub": "Entre para ver o histórico de suas tags.",
    "disclaimer": "Não afiliado ao Google. Independente e de código aberto. Para rastrear apenas seus próprios bens.",
    "login_failed": "Falha ao entrar",
    "no_tags": "Nenhuma tag ainda — aguardando a primeira verificação",
    "poll_off": "A verificação automática está desativada neste servidor.",
    "poll_check": "Consultando o Google em busca de localizações…",
    "poll_fail": "A última verificação falhou: {error}",
    "poll_last": "Última verificação {when} · a cada {minutes} min",
    "starting": "Iniciando…",
    "just_now": "agora mesmo",
    "mins_ago": "há {n} min",
    "hours_ago": "há {n} h",
    "days_ago": "há {n} dias",
    "free_upgrade": "🔒 Grátis — Atualizar",
    "parked": "🔒 Estacionado e protegido — toque ao sair",
    "drive_off": "Drive desligado",
    "drive_off_tip": "Sincronização com Google Drive desativada. Ative nas configurações.",
    "sync_pending": "Salvo · sincronização pendente",
    "sync_pending_tip": "As localizações estão salvas no celular. O Drive tentará novamente. ({error})",
    "synced": "Sincronizado",
    "backup_when": "Backup no Google Drive {when}",
    "drive_ready": "Drive pronto",
    "drive_ready_tip": "Google Drive configurado. O envio começa quando houver localizações.",
    "no_tag": "Nenhuma tag selecionada.",
    "no_locations": "Nenhuma localização armazenada ainda.",
    "last_seen": "Visto pela última vez em {when}",
    "finding_address": "📍 procurando endereço…",
    "locations_total": "{n} localizações armazenadas",
    "directions": "🧭 Como chegar",
    "latest": "mais recente",
    "open_maps": "Abrir no Maps",
    "route": "Rotas",
    "own_report": "Informado pelo seu celular",
    "network_report": "Informado pela rede Find Hub",
    "locations": "localizações",
    "distance": "km percorridos",
    "period": "período",
    "bad_dates": "Escolha um intervalo de datas válido.",
    "locating": "📍 Localizando…",
    "asking": "Solicitando localização atual da tag…",
    "guarding": "Proteção ativada: você será alertado se a tag se mover.",
    "riding": "Modo de deslocamento: alertas desativados.",
    "ringing": "Tocando a tag…",
    "no_share": "Ainda não há localização para compartilhar.",
    "last_seen_short": "vista pela última vez",
    "copied": "Localização copiada",
    "copy": "Copiar:",
    "new_name": "Novo nome para esta tag"
  }
};
  const KEY = 'tt_language';
  let language = 'en';
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'en' || saved === 'pt-BR') language = saved;
    else if ((navigator.language || '').toLowerCase().startsWith('pt')) language = 'pt-BR';
  } catch (_) { if ((navigator.language || '').toLowerCase().startsWith('pt')) language = 'pt-BR'; }
  function t(key, values = {}) {
    const str = (messages[language] && messages[language][key]) || messages.en[key] || key;
    return str.replace(/\{([a-zA-Z0-9_]+)\}/g, (_, k) => String(values[k] ?? ''));
  }
  function apply(root = document) {
    document.documentElement.lang = language;
    root.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.getAttribute('data-i18n')); });
    root.querySelectorAll('[data-i18n-title]').forEach((el) => { el.title = t(el.getAttribute('data-i18n-title')); });
    root.querySelectorAll('#tt-language').forEach((el) => { el.value = language; });
  }
  function setLanguage(next) {
    if (!['en', 'pt-BR'].includes(next) || next === language) return;
    language = next;
    try { localStorage.setItem(KEY, next); } catch (_) {}
    apply();
    window.dispatchEvent(new Event('tt-language-change'));
  }
  window.TT_I18N = Object.freeze({t, apply, setLanguage, get language() { return language; }});
  const init = () => {
    document.querySelectorAll('#tt-language').forEach((el) => el.addEventListener('change', () => setLanguage(el.value)));
    apply();
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
