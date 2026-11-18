const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '短信转飞书.js'), 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const factory = new AsyncFunction('args','config','Keychain','Request','Alert','Script', source+'\nreturn {main};');
const endpoint = 'https://open.feishu.cn/open-apis/bot/v2/hook/test-only-token';
async function runtime(input, options={}) {
  const calls=[];
  const Keychain={
    contains:key=>{assert.equal(key,'sms-forwarder.feishu.v1'); return !options.missing;},
    get:()=>options.saved === undefined ? JSON.stringify({webhook:endpoint,secret:'test-secret'}) : options.saved
  };
  class Request {
    constructor(url) {this.url=url; this.response={statusCode:options.status || 200}; calls.push(this);}
    async loadJSON() {
      if(options.failure) throw new Error('sensitive-url-or-secret');
      return options.reply === undefined ? {code:0} : options.reply;
    }
  }
  const lib=await factory({shortcutParameter:input},{runsInApp:false},Keychain,Request,undefined,undefined);
  return {lib,calls};
}
test('all nonempty text forwards unchanged regardless of content',async()=>{
  for(const body of ['晚上一起吃饭吗？','【其他平台】订单已送达','【银行】余额变动通知','123456789','【示例平台】普通通知','【示例商家】取餐通知','验证码为１２３４５６',' 测试\n中文🍜 "引号"\\ ']) {
    const {lib,calls}=await runtime(body);
    assert.deepEqual(await lib.main(),{sent:true});
    assert.equal(calls.length,1);
    const request=calls[0], payload=JSON.parse(request.body);
    assert.equal(payload.content.text,'短信转发\n来源：iPhone\n\n'+body);
    assert.equal(payload.sign,crypto.createHmac('sha256',payload.timestamp+'\ntest-secret').update('').digest('base64'));
    assert.equal(request.url,endpoint);
    assert.equal(request.method,'POST');
    assert.equal(request.onRedirect(),null);
    assert.equal(request.allowInsecureRequest,false);
  }
});
test('optional sender is preserved',async()=>{
  const {lib,calls}=await runtime({body:'短信正文',sender:'106000'});
  await lib.main();
  assert.equal(JSON.parse(calls[0].body).content.text,'短信转发\n来源：iPhone\n发件人：106000\n\n短信正文');
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
test('bad configuration and size limits fail before network',async()=>{
  for(const options of [{missing:true},{saved:'null'},{saved:'{'},{saved:JSON.stringify({webhook:'https://evil.example',secret:''})}]) {
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
test('errors never report success, leak network details or retry',async()=>{
  for(const options of [{failure:true},{status:500},{reply:{}},{reply:{code:19021}}]) {
    const {lib,calls}=await runtime('普通短信',options);
    await assert.rejects(lib.main(),error=>!error.message.includes('sensitive') && !error.message.includes('test-secret'));
    assert.equal(calls.length,1);
  }
});
test('running inside Scriptable without input only saves configuration',async()=>{
  let saved;
  class Alert {
    addTextField() {} addSecureTextField() {} addAction() {} addCancelAction() {}
    async presentAlert() {return 0;}
    textFieldValue(index) {return index===0 ? endpoint : 'test-secret';}
  }
  const lib=await factory({}, {runsInApp:true}, {set:(key,value)=>{saved={key,value};}},
    class Request {constructor(){throw new Error('configuration must not send');}}, Alert, undefined);
  assert.deepEqual(await lib.main(),{configured:true});
  assert.equal(saved.key,'sms-forwarder.feishu.v1');
  assert.deepEqual(JSON.parse(saved.value),{webhook:endpoint,secret:'test-secret'});
});
