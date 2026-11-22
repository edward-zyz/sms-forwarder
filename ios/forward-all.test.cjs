const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '短信转飞书.js'), 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const factory = new AsyncFunction('args','config','Keychain','Request','Alert','Script', source+'\nreturn {main, detectPlatform};');
const feishuUrl = 'https://open.feishu.cn/open-apis/bot/v2/hook/test-only-token';
const wecomUrl = 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-wecom-key';
const dingtalkUrl = 'https://oapi.dingtalk.com/robot/send?access_token=test-dingtalk-token';
const customUrl = 'https://hooks.example.com/sms/receive';

async function runtime(input, options={}) {
  const calls=[];
  const Keychain={
    contains:key=>{assert.equal(key,'sms-forwarder.feishu.v1'); return !options.missing;},
    get:()=>options.saved === undefined ? JSON.stringify({webhook:feishuUrl,secret:'test-secret'}) : options.saved
  };
  class Request {
    constructor(url) {this.url=url; this.response={statusCode:options.status || 200}; calls.push(this);}
    async loadJSON() {
      if(options.failure) throw new Error('sensitive-url-or-secret');
      return options.reply === undefined ? {code:0} : options.reply;
    }
    async loadString() {
      if(options.failure) throw new Error('sensitive-url-or-secret');
      return options.textReply === undefined ? 'ok' : options.textReply;
    }
  }
  const lib=await factory({shortcutParameter:input},{runsInApp:false},Keychain,Request,undefined,undefined);
  return {lib,calls};
}

test('feishu: all nonempty text forwards unchanged with HMAC signature',async()=>{
  for(const body of ['晚上一起吃饭吗？','【其他平台】订单已送达','【银行】余额变动通知','123456789','【示例平台】普通通知','【示例商家】取餐通知','验证码为１２３４５６',' 测试\n中文🍜 "引号"\\ ']) {
    const {lib,calls}=await runtime(body);
    assert.deepEqual(await lib.main(),{sent:true,platform:'feishu'});
    assert.equal(calls.length,1);
    const request=calls[0], payload=JSON.parse(request.body);
    assert.equal(payload.msg_type,'text');
    assert.equal(payload.content.text,'短信转发\n来源：iPhone\n\n'+body);
    assert.equal(payload.sign,crypto.createHmac('sha256',payload.timestamp+'\ntest-secret').update('').digest('base64'));
    assert.equal(request.url,feishuUrl);
    assert.equal(request.method,'POST');
    assert.equal(request.onRedirect(),null);
    assert.equal(request.allowInsecureRequest,false);
  }
});

test('wecom: forwards text payload and checks errcode === 0',async()=>{
  const {lib,calls}=await runtime({body:'【美团】验证码 482910',sender:'106900'},{
    saved:JSON.stringify({webhook:wecomUrl,secret:'ignored',devicePhone:'浦东店'}),
    reply:{errcode:0,errmsg:'ok'}
  });
  assert.deepEqual(await lib.main(),{sent:true,platform:'wecom'});
  assert.equal(calls.length,1);
  assert.equal(calls[0].url,wecomUrl);
  assert.deepEqual(JSON.parse(calls[0].body),{
    msgtype:'text',
    text:{content:'短信转发\n来源：iPhone\n接收手机：浦东店\n发件人：106900\n\n【美团】验证码 482910'}
  });
});

test('dingtalk: signs query params with millis and checks errcode === 0',async()=>{
  const {lib,calls}=await runtime('【抖音来客】验证码 654321',{
    saved:JSON.stringify({webhook:dingtalkUrl,secret:'SECtest-secret'}),
    reply:{errcode:0,errmsg:'ok'}
  });
  assert.deepEqual(await lib.main(),{sent:true,platform:'dingtalk'});
  assert.equal(calls.length,1);
  const parsed = new URL(calls[0].url);
  assert.equal(parsed.origin+parsed.pathname,'https://oapi.dingtalk.com/robot/send');
  assert.equal(parsed.searchParams.get('access_token'),'test-dingtalk-token');
  const ts = parsed.searchParams.get('timestamp');
  assert.match(ts,/^\d{13}$/);
  const expectedSign = crypto.createHmac('sha256','SECtest-secret').update(ts+'\nSECtest-secret').digest('base64');
  assert.equal(parsed.searchParams.get('sign'),expectedSign);
  assert.deepEqual(JSON.parse(calls[0].body),{
    msgtype:'text',
    text:{content:'短信转发\n来源：iPhone\n\n【抖音来客】验证码 654321'}
  });
});

test('dingtalk without secret does not append timestamp or sign',async()=>{
  const {lib,calls}=await runtime('测试消息',{
    saved:JSON.stringify({webhook:dingtalkUrl,secret:''}),
    reply:{errcode:0}
  });
  assert.deepEqual(await lib.main(),{sent:true,platform:'dingtalk'});
  assert.equal(calls[0].url,dingtalkUrl);
});

test('custom webhook: forwards JSON, signs headers, accepts HTTP 2xx non-JSON reply',async()=>{
  const {lib,calls}=await runtime('【淘宝】验证码 112233',{
    saved:JSON.stringify({webhook:customUrl,secret:'my-webhook-secret',devicePhone:'备用机2'}),
    status:204,
    textReply:''
  });
  assert.deepEqual(await lib.main(),{sent:true,platform:'custom'});
  assert.equal(calls.length,1);
  const req = calls[0];
  assert.equal(req.url,customUrl);
  const payload = JSON.parse(req.body);
  assert.equal(payload.title,'短信转发');
  assert.equal(payload.text,'短信转发\n来源：iPhone\n接收手机：备用机2\n\n【淘宝】验证码 112233');
  assert.equal(typeof payload.timestamp,'number');
  assert.equal(req.headers['X-Webhook-Timestamp'],String(payload.timestamp));
  const expected = crypto.createHmac('sha256','my-webhook-secret').update(payload.timestamp+'\n'+req.body).digest('base64');
  assert.equal(req.headers['X-Webhook-Signature'],expected);
});

test('optional sender and devicePhone are sanitized and preserved',async()=>{
  const {lib,calls}=await runtime({body:'验证码 482910',sender:'106900'},{
    saved:JSON.stringify({webhook:feishuUrl,secret:'test-secret',devicePhone:' 浦东店\n138****8888 '})
  });
  await lib.main();
  assert.equal(JSON.parse(calls[0].body).content.text,'短信转发\n来源：iPhone\n接收手机：浦东店 138****8888\n发件人：106900\n\n验证码 482910');
});

test('blank text skips and missing or invalid input cannot send',async()=>{
  for(const input of ['  \n',{body:''}]) {
    const {lib,calls}=await runtime(input);
    assert.deepEqual(await lib.main(),{skipped:true,reason:'短信正文为空'});
    assert.equal(calls.length,0);
  }
  for(const input of [undefined,null,'',123,{text:'正文'}]) {
    const {lib,calls}=await runtime(input);
    await assert.rejects(lib.main());
    assert.equal(calls.length,0);
  }
});

test('bad configuration, invalid URLs and size limits fail before network',async()=>{
  const badUrls = [
    'http://hooks.example.com/sms',
    'https://user:pass@hooks.example.com/sms',
    'https://hooks.example.com/sms#frag',
    'https://open.feishu.cn/wrong/path',
    'https://qyapi.weixin.qq.com/cgi-bin/webhook/send',
    'https://oapi.dingtalk.com/robot/send?foo=bar',
    'not-a-url',
    'https://hooks.example.com/' + 'a'.repeat(2050)
  ];
  for(const options of [{missing:true},{saved:'null'},{saved:'{'},...badUrls.map(webhook=>({saved:JSON.stringify({webhook,secret:''})}))]) {
    const {lib,calls}=await runtime('普通短信',options);
    await assert.rejects(lib.main());
    assert.equal(calls.length,0);
  }
  for(const body of ['中'.repeat(5334),'\u0001'.repeat(4000)]) {
    const {lib,calls}=await runtime(body);
    await assert.rejects(lib.main(),/超过/);
    assert.equal(calls.length,0);
  }
});

test('errors never report success, leak network details or retry across platforms',async()=>{
  const cases = [
    {failure:true},
    {status:500},
    {reply:{}},
    {reply:{code:19021}},
    {saved:JSON.stringify({webhook:wecomUrl,secret:''}),reply:{errcode:93000}},
    {saved:JSON.stringify({webhook:dingtalkUrl,secret:'SECtest'}),reply:{errcode:310000}},
    {saved:JSON.stringify({webhook:customUrl,secret:'s'}),status:403},
    {saved:JSON.stringify({webhook:customUrl,secret:'s'}),failure:true}
  ];
  for(const options of cases) {
    const {lib,calls}=await runtime('普通短信',options);
    await assert.rejects(lib.main(),error=>!error.message.includes('sensitive') && !error.message.includes('test-secret') && !error.message.includes('SECtest'));
    assert.equal(calls.length,1);
  }
});

test('running inside Scriptable without input only saves configuration and detects platform',async()=>{
  for (const [url, expectedPlatform] of [[feishuUrl,'feishu'],[wecomUrl,'wecom'],[dingtalkUrl,'dingtalk'],[customUrl,'custom']]) {
    let saved;
    class Alert {
      addTextField() {} addSecureTextField() {} addAction() {} addCancelAction() {}
      async presentAlert() {return 0;}
      textFieldValue(index) {return [url,'test-secret',' 138****8888 '][index] || '';}
    }
    const lib=await factory({}, {runsInApp:true}, {set:(key,value)=>{saved={key,value};}},
      class Request {constructor(){throw new Error('configuration must not send');}}, Alert, undefined);
    assert.deepEqual(await lib.main(),{configured:true,platform:expectedPlatform});
    assert.equal(saved.key,'sms-forwarder.feishu.v1');
    assert.deepEqual(JSON.parse(saved.value),{webhook:url,secret:'test-secret',devicePhone:'138****8888'});
  }
});
