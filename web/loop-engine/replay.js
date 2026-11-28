/* Deterministic replay of a recorded run — the web equivalent of Kotlin's RecordingGatewayPolicy used for replay.
   A recording (`execute(...).recording`) holds every node output (with its reported usage) and every gateway decision
   in order. Replaying re-walks the same definition with executors that answer from the recording and a gateway
   policy that replays the recorded choices: no model, tool or human is consulted, and the run reproduces the same
   step sequence, outputs, terminal state and terminal reason. Divergence (a different definition, a node asked for
   more outputs than were recorded, a gateway reached out of order) throws ReplayDivergenceError. */

import { LoopRunDriver } from './run-driver.js';
import { ReplayDivergenceError } from './gateway.js';
import { semanticDigest } from './digest.js';

export { ReplayDivergenceError };

export async function replayRun(def, recording, { driver = null, ...opts } = {}) {
  if (!recording || recording.format !== 'loop-recording/1') throw new ReplayDivergenceError('not a loop recording');
  const d = (await semanticDigest(def)).digest;
  if (recording.definitionDigest !== d) throw new ReplayDivergenceError(`definition changed since the recording (${recording.definitionDigest} vs ${d})`);
  const drv = driver || new LoopRunDriver({});
  const out = await drv.execute(def, recording.objective, { params: recording.params, ...opts, replay: recording });
  const diffs = compareRuns(recording, out);
  if (diffs.length) throw new ReplayDivergenceError('replay diverged: ' + diffs.join('; '));
  return out;
}

/** Differences between a recording and a replayed execute() result (empty = identical behaviour). */
export function compareRuns(recording, replayed) {
  const diffs = [];
  const a = recording.steps, b = replayed.result.steps;
  if (a.length !== b.length) diffs.push(`step count ${a.length} vs ${b.length}`);
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    if (a[i].nodeId !== b[i].nodeId) diffs.push(`step ${i}: node ${a[i].nodeId} vs ${b[i].nodeId}`);
    else if (JSON.stringify(a[i].output) !== JSON.stringify(b[i].output)) diffs.push(`step ${i}: output differs`);
  }
  if (JSON.stringify(recording.gateways) !== JSON.stringify(replayed.recording.gateways)) diffs.push('gateway decisions differ');
  if (recording.stoppedBecause !== replayed.result.stoppedBecause) diffs.push(`stop reason '${recording.stoppedBecause}' vs '${replayed.result.stoppedBecause}'`);
  return diffs;
}
