import { launch } from './lib/launch.mjs'

const [command, ...options] = process.argv.slice(2)
if (command && command !== 'server') throw new Error('Use jagent --help')
await launch(command ? 'host' : 'all', options)
