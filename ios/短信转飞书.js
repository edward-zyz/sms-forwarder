// 转发快捷指令传入的非空短信正文，触发范围由快捷指令设置。
// 支持飞书、企业微信、钉钉与自定义 HTTPS Webhook（按地址自动识别平台）。
// 自动化的触发范围需在快捷指令中另行设置；脚本不能自行读取短信收件箱。
// 保持 Scriptable 钥匙串配置键不变以兼容旧版；不保存短信，不自动重试。
const CONFIG_KEY = 'sms-forwarder.feishu.v1';

const PLATFORM_NAMES = {
  feishu: '飞书',
  wecom: '企业微信',
  dingtalk: '钉钉',
  custom: '自定义 Webhook',
};

// 显式单次执行状态机阶段与合法转换表（与 formal/IosForwardPipeline.tla 一一对应）。
const PIPELINE_STAGES = Object.freeze({
  INIT: 'INIT',
  CONFIGURE: 'CONFIGURE',
  VALIDATE_INPUT: 'VALIDATE_INPUT',
  LOAD_CONFIG: 'LOAD_CONFIG',
  BUILD_REQUEST: 'BUILD_REQUEST',
  HTTP_POST: 'HTTP_POST',
  CHECK_RECEIPT: 'CHECK_RECEIPT',
  CONFIGURED: 'CONFIGURED',
  CONFIG_CANCELLED: 'CONFIG_CANCELLED',
  SKIPPED: 'SKIPPED',
  SENT: 'SENT',
  FAILED: 'FAILED',
});

const PIPELINE_TRANSITIONS = Object.freeze({
  INIT: ['CONFIGURE', 'VALIDATE_INPUT', 'FAILED'],
  CONFIGURE: ['CONFIGURED', 'CONFIG_CANCELLED', 'FAILED'],
  VALIDATE_INPUT: ['LOAD_CONFIG', 'SKIPPED', 'FAILED'],
  LOAD_CONFIG: ['BUILD_REQUEST', 'FAILED'],
  BUILD_REQUEST: ['HTTP_POST', 'FAILED'],
  HTTP_POST: ['CHECK_RECEIPT', 'FAILED'],
  CHECK_RECEIPT: ['SENT', 'FAILED'],
  CONFIGURED: [],
  CONFIG_CANCELLED: [],
  SKIPPED: [],
  SENT: [],
  FAILED: [],
});

function createPipelineTracker() {
  let stage = PIPELINE_STAGES.INIT;
  return {
    get stage() { return stage; },
    transition(nextStage) {
      const allowed = PIPELINE_TRANSITIONS[stage] || [];
      if (!allowed.includes(nextStage)) {
        throw new Error('非法状态迁移：' + stage + ' -> ' + nextStage);
      }
      stage = nextStage;
      return stage;
    },
  };
}

function utf8(text) {
  const out = [];
  for (let i = 0; i < text.length; i++) {
    let c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = text.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + next - 0xdc00; i++; }
      else c = 0xfffd;
    } else if (c >= 0xdc00 && c <= 0xdfff) c = 0xfffd;
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return out;
}

function sha256(input) {
  const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const h = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
  const data = Array.from(input);
  const bits = data.length * 8;
  data.push(0x80);
  while (data.length % 64 !== 56) data.push(0);
  const high = Math.floor(bits / 0x100000000), low = bits >>> 0;
  for (let i = 3; i >= 0; i--) data.push((high >>> (i * 8)) & 255);
  for (let i = 3; i >= 0; i--) data.push((low >>> (i * 8)) & 255);
  const r = (x, n) => (x >>> n) | (x << (32 - n));
  for (let offset = 0; offset < data.length; offset += 64) {
    const w = [];
    for (let i = 0; i < 16; i++) {
      const j = offset + i * 4;
      w[i] = (data[j] << 24) | (data[j+1] << 16) | (data[j+2] << 8) | data[j+3];
    }
    for (let i = 16; i < 64; i++) {
      const s0 = r(w[i-15],7) ^ r(w[i-15],18) ^ (w[i-15] >>> 3);
      const s1 = r(w[i-2],17) ^ r(w[i-2],19) ^ (w[i-2] >>> 10);
      w[i] = (w[i-16] + s0 + w[i-7] + s1) | 0;
    }
    let [a,b,c,d,e,f,g,v] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (v + (r(e,6) ^ r(e,11) ^ r(e,25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
      const t2 = ((r(a,2) ^ r(a,13) ^ r(a,22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      v=g; g=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+t2)|0;
    }
    [a,b,c,d,e,f,g,v].forEach((n,i) => { h[i] = (h[i] + n) | 0; });
  }
  const out = [];
  h.forEach(n => { for (let i=3; i>=0; i--) out.push((n >>> (i*8)) & 255); });
  return out;
}

function hmacSha256(key, message) {
  let bytes = Array.from(key);
  if (bytes.length > 64) bytes = sha256(bytes);
  while (bytes.length < 64) bytes.push(0);
  return sha256(bytes.map(n => n ^ 0x5c).concat(sha256(bytes.map(n => n ^ 0x36).concat(message))));
}
function base64(bytes) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let out = '';
  for (let i=0; i<bytes.length; i+=3) {
    const n=(bytes[i]<<16)|((bytes[i+1]||0)<<8)|(bytes[i+2]||0);
    out += chars[(n>>>18)&63]+chars[(n>>>12)&63]+(i+1<bytes.length?chars[(n>>>6)&63]:'=')+(i+2<bytes.length?chars[n&63]:'=');
  }
  return out;
}
function detectPlatform(url) {
  if (typeof url !== 'string' || !url || url.length > 2048) return null;
  if (/[\s#]/.test(url)) return null;
  const match = /^https:\/\/([^/?#]+)(\/[^#]*)?$/.exec(url);
  if (!match) return null;
  const authority = match[1];
  if (authority.includes('@')) return null;
  const hostPort = authority.split(':');
  if (hostPort.length > 2) return null;
  const host = hostPort[0].toLowerCase();
  const port = hostPort.length === 2 ? hostPort[1] : '';
  if (!host || !/^[a-z0-9.-]+$/.test(host) || host.startsWith('.') || host.endsWith('.') || host.includes('..')) return null;
  if (port && (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535)) return null;
  if (host === 'open.feishu.cn') {
    return /^https:\/\/open\.feishu\.cn\/open-apis\/bot\/v2\/hook\/[A-Za-z0-9_-]{8,128}$/.test(url) ? 'feishu' : null;
  }
  if (host === 'qyapi.weixin.qq.com') {
    return /^https:\/\/qyapi\.weixin\.qq\.com\/cgi-bin\/webhook\/send\?key=[A-Za-z0-9_-]{8,128}$/.test(url) ? 'wecom' : null;
  }
  if (host === 'oapi.dingtalk.com') {
    return /^https:\/\/oapi\.dingtalk\.com\/robot\/send\?access_token=[A-Za-z0-9_-]{8,128}$/.test(url) ? 'dingtalk' : null;
  }
  return 'custom';
}
function validWebhook(url) {
  return detectPlatform(url) !== null;
}
function readMessage(input) {
  if (typeof input === 'string') return {body: input, sender: ''};
  if (input && typeof input === 'object' && typeof input.body === 'string') {
    return {body: input.body, sender: typeof input.sender === 'string' ? input.sender : ''};
  }
  throw new Error('请将快捷指令收到的短信“内容”文本作为脚本参数传入。');
}
function sanitizeDevicePhone(raw) {
  if (typeof raw !== 'string') return '';
  return raw.replace(/[\r\n]+/g, ' ').trim().slice(0, 48).trimEnd();
}
function formatText(message, devicePhone) {
  const cleanDevice = sanitizeDevicePhone(devicePhone);
  return '短信转发\n来源：iPhone' +
    (cleanDevice ? '\n接收手机：' + cleanDevice : '') +
    (message.sender ? '\n发件人：' + message.sender : '') +
    '\n\n' + message.body;
}
function buildRequest(platform, webhook, secret, message, nowMillis, devicePhone) {
  const seconds = Math.floor(nowMillis / 1000);
  const text = formatText(message, devicePhone);
  const headers = {'Content-Type': 'application/json; charset=utf-8'};
  if (platform === 'feishu') {
    const payload = {msg_type: 'text', content: {text}};
    if (secret) {
      payload.timestamp = String(seconds);
      // 飞书：timestamp + 换行 + secret 作为 HMAC 密钥，对空消息签名。
      payload.sign = base64(hmacSha256(utf8(seconds + '\n' + secret), []));
    }
    return {url: webhook, body: JSON.stringify(payload), headers};
  }
  if (platform === 'wecom') {
    const payload = {msgtype: 'text', text: {content: text}};
    return {url: webhook, body: JSON.stringify(payload), headers};
  }
  if (platform === 'dingtalk') {
    const payload = {msgtype: 'text', text: {content: text}};
    let url = webhook;
    if (secret) {
      // 钉钉：secret 作为 HMAC 密钥，对 毫秒时间戳 + 换行 + secret 签名后 Base64 并 URL 编码。
      const sign = encodeURIComponent(base64(hmacSha256(utf8(secret), utf8(nowMillis + '\n' + secret))));
      url = webhook + '&timestamp=' + nowMillis + '&sign=' + sign;
    }
    return {url, body: JSON.stringify(payload), headers};
  }
  // 自定义 Webhook：与 Android 端协议一致。
  const payload = {title: '短信转发', text, timestamp: seconds};
  const body = JSON.stringify(payload);
  if (secret) {
    headers['X-Webhook-Timestamp'] = String(seconds);
    headers['X-Webhook-Signature'] = base64(hmacSha256(utf8(secret), utf8(seconds + '\n' + body)));
  }
  return {url: webhook, body, headers};
}
async function configure() {
  const alert = new Alert();
  alert.title = '短信转发';
  alert.message = '支持飞书、企业微信、钉钉与自定义 HTTPS Webhook（自动识别平台）。配置仅保存在本机 Scriptable 钥匙串；本次只保存配置。';
  alert.addTextField('群机器人或自定义 HTTPS Webhook');
  alert.addSecureTextField('签名密钥（飞书/钉钉加签或自定义签名时填写，企微留空）');
  alert.addTextField('本机号码或备注（选填，多机转同一群时区分来源）');
  alert.addAction('保存');
  alert.addCancelAction('取消');
  if (await alert.presentAlert() < 0) return {configured:false};
  const webhook=alert.textFieldValue(0).trim(), secret=alert.textFieldValue(1).trim(), devicePhone=sanitizeDevicePhone(alert.textFieldValue(2));
  const platform = detectPlatform(webhook);
  if (!platform) throw new Error('Webhook 地址格式不正确（需为合法的飞书/企微/钉钉或自定义 HTTPS 地址），未保存。');
  Keychain.set(CONFIG_KEY, JSON.stringify({webhook,secret,devicePhone}));
  return {configured:true, platform};
}
async function main() {
  const tracker = createPipelineTracker();
  const input = args.shortcutParameter;
  if (input == null || input === '') {
    if (config.runsInApp) {
      tracker.transition(PIPELINE_STAGES.CONFIGURE);
      try {
        const res = await configure();
        tracker.transition(res.configured ? PIPELINE_STAGES.CONFIGURED : PIPELINE_STAGES.CONFIG_CANCELLED);
        return res;
      } catch (err) {
        tracker.transition(PIPELINE_STAGES.FAILED);
        throw err;
      }
    }
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('缺少短信内容。请在快捷指令中将短信“内容”文本传入脚本参数；手动点击运行不会自动读取最新短信。');
  }
  tracker.transition(PIPELINE_STAGES.VALIDATE_INPUT);
  let message;
  try {
    message = readMessage(input);
  } catch (err) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw err;
  }
  if (!message.body.trim()) {
    tracker.transition(PIPELINE_STAGES.SKIPPED);
    return {skipped:true, reason:'短信正文为空'};
  }
  if (utf8(message.body).length > 16000) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('短信超过16KB，未转发。');
  }
  tracker.transition(PIPELINE_STAGES.LOAD_CONFIG);
  if (!Keychain.contains(CONFIG_KEY)) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('请先在 Scriptable 中直接运行脚本，保存 Webhook 配置。');
  }
  let saved;
  try { saved=JSON.parse(Keychain.get(CONFIG_KEY)); } catch (_) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('配置不可用，请在 Scriptable 中重新配置。');
  }
  const webhook = saved && typeof saved.webhook === 'string' ? saved.webhook.trim() : '';
  const platform = detectPlatform(webhook);
  if (!saved || !platform || typeof saved.secret !== 'string') {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('Webhook 配置无效，请重新配置。');
  }
  tracker.transition(PIPELINE_STAGES.BUILD_REQUEST);
  const secret = saved.secret.trim();
  const built = buildRequest(platform, webhook, secret, message, Date.now(), saved.devicePhone);
  if (utf8(built.body).length > 20000) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error('消息超过20KB限制，未转发。');
  }
  tracker.transition(PIPELINE_STAGES.HTTP_POST);
  const request = new Request(built.url);
  request.method='POST';
  request.headers=built.headers;
  request.body=built.body;
  request.timeoutInterval=15;
  request.allowInsecureRequest=false;
  request.onRedirect=() => null;
  const label = PLATFORM_NAMES[platform];
  if (platform === 'custom') {
    try { await request.loadString(); }
    catch (_) {
      tracker.transition(PIPELINE_STAGES.FAILED);
      throw new Error(label + '请求未取得有效回执。请先查看目标服务，避免手动重试时重复发送。');
    }
    tracker.transition(PIPELINE_STAGES.CHECK_RECEIPT);
    const status = request.response && request.response.statusCode;
    if (!Number.isInteger(status) || status < 200 || status > 299) {
      tracker.transition(PIPELINE_STAGES.FAILED);
      throw new Error(label + '未确认成功（HTTP ' + (status || '未知') + '）。请检查地址与签名设置。');
    }
    tracker.transition(PIPELINE_STAGES.SENT);
    return {sent:true, platform};
  }
  let response;
  try { response=await request.loadJSON(); }
  catch (_) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    throw new Error(label + '请求未取得有效回执。请先查看群消息，避免手动重试时重复发送。');
  }
  tracker.transition(PIPELINE_STAGES.CHECK_RECEIPT);
  const okField = platform === 'feishu' ? 'code' : 'errcode';
  if (!request.response || request.response.statusCode !== 200 || !response || response[okField] !== 0) {
    tracker.transition(PIPELINE_STAGES.FAILED);
    const code=response && Number.isInteger(response[okField]) ? response[okField] : '未知';
    throw new Error(label + '未确认成功（错误码 '+code+'）。请检查签名密钥、机器人关键词和群设置。');
  }
  tracker.transition(PIPELINE_STAGES.SENT);
  return {sent:true, platform};
}

if (typeof Script !== 'undefined') {
  const result = await main();
  Script.setShortcutOutput(result);
  Script.complete();
}
