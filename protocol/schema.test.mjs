import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import Ajv from 'ajv'
const schema=JSON.parse(readFileSync(new URL('./schema.json',import.meta.url)))
const ajv=new Ajv({strict:false}); ajv.addSchema(schema,'protocol')
const cases={'user-request':'ChatRequest','tool-results':'ChatRequest','requires-action':'ChatResponse','completed':'ChatResponse','stream-event':'StreamEvent'}
for(const [file,type] of Object.entries(cases)) test(file,()=>{
 const validate=ajv.getSchema(`protocol#/definitions/${type}`)
 assert.ok(validate(JSON.parse(readFileSync(new URL(`./fixtures/${file}.json`,import.meta.url)))),JSON.stringify(validate.errors))
})
test('mixed roles are rejected',()=>{
 const validate=ajv.getSchema('protocol#/definitions/ChatRequest')
 assert.equal(validate({sessionId:'s',messages:[{role:'user',content:'hi'},{role:'tool',toolCallId:'c',status:'SUCCEEDED',content:'ok'}]}),false)
})
