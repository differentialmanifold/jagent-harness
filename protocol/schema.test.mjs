import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import Ajv from 'ajv'
const schema=JSON.parse(readFileSync(new URL('./schema.json',import.meta.url)))
const ajv=new Ajv({strict:false}); ajv.addSchema(schema,'protocol')
const cases={'user-request':'ChatRequest','tool-results':'ChatRequest','requires-action':'ChatResponse','completed':'ChatResponse','stream-event':'StreamEvent','runner-registration':'RunnerInfo','runner-poll':'RunnerPoll'}
for(const [file,type] of Object.entries(cases)) test(file,()=>{
 const validate=ajv.getSchema(`protocol#/definitions/${type}`)
 assert.ok(validate(JSON.parse(readFileSync(new URL(`./fixtures/${file}.json`,import.meta.url)))),JSON.stringify(validate.errors))
})
test('mixed roles are rejected',()=>{
 const validate=ajv.getSchema('protocol#/definitions/ChatRequest')
 assert.equal(validate({sessionId:'s',messages:[{role:'user',content:'hi'},{role:'tool',toolCallId:'c',status:'SUCCEEDED',content:'ok'}]}),false)
})
test('runners and jobs do not require a workspace',()=>{
 const runner=JSON.parse(readFileSync(new URL('./fixtures/runner-registration.json',import.meta.url)))
 delete runner.workspace
 const validateRunner=ajv.getSchema('protocol#/definitions/RunnerInfo')
 assert.ok(validateRunner(runner),JSON.stringify(validateRunner.errors))
 runner.workspace=null
 assert.ok(validateRunner(runner),JSON.stringify(validateRunner.errors))
 const poll=JSON.parse(readFileSync(new URL('./fixtures/runner-poll.json',import.meta.url)))
 delete poll.job.workspace
 const validatePoll=ajv.getSchema('protocol#/definitions/RunnerPoll')
 assert.ok(validatePoll(poll),JSON.stringify(validatePoll.errors))
 poll.job.workspace=null
 assert.ok(validatePoll(poll),JSON.stringify(validatePoll.errors))
})
