const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {test} = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '短信转飞书.js'), 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const factory = new AsyncFunction(
  'args', 'config', 'Keychain', 'Request', 'Alert', 'Script',
  source + '\nreturn {PIPELINE_STAGES, PIPELINE_TRANSITIONS, createPipelineTracker};'
);

test('iOS pipeline stages and transition table match formal/IosForwardPipeline.tla', async () => {
  const {PIPELINE_STAGES, PIPELINE_TRANSITIONS, createPipelineTracker} = await factory(
    {}, {runsInApp: false}, undefined, undefined, undefined, undefined
  );

  const expectedStages = [
    'INIT', 'CONFIGURE', 'VALIDATE_INPUT', 'LOAD_CONFIG',
    'BUILD_REQUEST', 'HTTP_POST', 'CHECK_RECEIPT',
    'CONFIGURED', 'CONFIG_CANCELLED', 'SKIPPED', 'SENT', 'FAILED',
  ];
  assert.deepEqual(Object.values(PIPELINE_STAGES).sort(), expectedStages.sort());

  // 终态没有任何出边
  for (const terminal of ['CONFIGURED', 'CONFIG_CANCELLED', 'SKIPPED', 'SENT', 'FAILED']) {
    assert.deepEqual(PIPELINE_TRANSITIONS[terminal], []);
  }

  // 合法路径：完整成功发送链路
  const okTracker = createPipelineTracker();
  assert.equal(okTracker.stage, 'INIT');
  for (const next of ['VALIDATE_INPUT', 'LOAD_CONFIG', 'BUILD_REQUEST', 'HTTP_POST', 'CHECK_RECEIPT', 'SENT']) {
    assert.equal(okTracker.transition(next), next);
  }
  // 终态不允许再次迁移（防止重试）
  assert.throws(() => okTracker.transition('HTTP_POST'), /非法状态迁移/);

  // 非法跃迁：跳过前置校验直接发网络必须被拒绝
  const illegalTracker = createPipelineTracker();
  assert.throws(() => illegalTracker.transition('HTTP_POST'), /非法状态迁移/);
  assert.throws(() => illegalTracker.transition('SENT'), /非法状态迁移/);
});
