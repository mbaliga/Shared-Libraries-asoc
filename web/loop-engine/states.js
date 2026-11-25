/* Run state machine — LOOP_ENGINEERING_SPEC_V2.1 §9 (17 states: 9 non-terminal, 8 terminal).
   Port of contracts/kotlin/LoopRuntimeContracts.kt `RunState` + `TerminalReasonCategory`. The transition table is
   reduced to (from, to) pairs exactly as the Kotlin contract does. */

export const NON_TERMINAL = Object.freeze([
  'CREATED', 'PREFLIGHT', 'WAITING_BINDING', 'WAITING_AUTHORITY', 'READY', 'RUNNING',
  'WAITING_USER', 'SUSPENDED', 'CANCELLING',
]);

export const TERMINAL = Object.freeze([
  'SUCCEEDED_VERIFIED', 'SUCCEEDED_UNVERIFIED', 'STOPPED_BUDGET', 'STOPPED_POLICY', 'FAILED_SAFE',
  'FAILED_SIDE_EFFECTS_POSSIBLE', 'TARGET_STATE_UNKNOWN', 'CANCELLED',
]);

export const RUN_STATES = Object.freeze([...NON_TERMINAL, ...TERMINAL]);

const TRANSITIONS = {
  CREATED: ['PREFLIGHT'],
  PREFLIGHT: ['WAITING_BINDING', 'WAITING_AUTHORITY', 'FAILED_SAFE'],
  WAITING_BINDING: ['WAITING_AUTHORITY', 'CANCELLING'],
  WAITING_AUTHORITY: ['READY', 'CANCELLING'],
  READY: ['RUNNING'],
  RUNNING: [
    'WAITING_USER', 'SUSPENDED', 'CANCELLING', 'STOPPED_BUDGET', 'STOPPED_POLICY',
    'SUCCEEDED_VERIFIED', 'SUCCEEDED_UNVERIFIED', 'FAILED_SAFE',
    'FAILED_SIDE_EFFECTS_POSSIBLE', 'TARGET_STATE_UNKNOWN',
  ],
  WAITING_USER: ['RUNNING', 'CANCELLING'],
  SUSPENDED: ['RUNNING', 'CANCELLING'],
  CANCELLING: ['CANCELLED', 'TARGET_STATE_UNKNOWN'],
};

export function isTerminal(state) { return TERMINAL.includes(state); }

/** True iff `to` is a state `from` may transition to directly. A terminal state has no outgoing edge. */
export function isValidTransition(from, to) {
  return !isTerminal(from) && (TRANSITIONS[from] || []).includes(to);
}

export function validTransitionsFrom(from) { return isTerminal(from) ? [] : [...(TRANSITIONS[from] || [])]; }

export const TERMINAL_REASON_CATEGORIES = Object.freeze([
  'VALIDATION_FAILURE', 'CANCELLATION_REQUESTED', 'CANCELLATION_TARGET_UNCONFIRMED',
  'BUDGET_STEPS_EXHAUSTED', 'BUDGET_WALL_CLOCK_EXHAUSTED', 'BUDGET_TOKENS_EXHAUSTED',
  'BUDGET_COST_EXHAUSTED', 'BUDGET_TOOL_CALLS_EXHAUSTED', 'POLICY_BOUNDARY',
  'ALL_REQUIRED_VERIFIERS_PASSED', 'REQUIRED_VERIFIER_INCOMPLETE',
  'NODE_FAILURE_NO_SIDE_EFFECTS', 'NODE_FAILURE_SIDE_EFFECTS_POSSIBLE',
  'SIDE_EFFECT_OUTCOME_UNCONFIRMED',
]);

/** Checks a whole event log: first event is CREATED with no fromState, every later event chains from the previous
 *  toState and is a legal §9 transition. Returns a list of problems (empty = legal). Mirrors Kotlin's RunEvent
 *  constructor guard plus LoopRunDriverTest.assertLegalEventSequence. */
export function checkEventSequence(events) {
  const problems = [];
  if (!events.length) return ['event log is empty'];
  if (events[0].toState !== 'CREATED') problems.push('first event must enter CREATED');
  if (events[0].fromState != null) problems.push('initiating event must have no fromState');
  for (let i = 1; i < events.length; i++) {
    const e = events[i];
    if (e.fromState !== events[i - 1].toState) problems.push(`event ${i}: fromState ${e.fromState} does not follow ${events[i - 1].toState}`);
    if (!isValidTransition(e.fromState, e.toState)) problems.push(`event ${i}: illegal transition ${e.fromState} -> ${e.toState}`);
  }
  return problems;
}
