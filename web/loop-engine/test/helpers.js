// Shared builders for the engine tests. Definitions here are fully schema-valid LoopDefinition v2 objects so that
// every run also exercises preflight static validation.

export function node(id, category = 'MODEL_INFERENCE', over = {}) {
  const cfg = over.config;
  return {
    nodeId: id, category, inputPorts: [], outputPorts: [], implementation: { kind: 'TYPED_SLOT', slotId: `${id}-slot` },
    requestedCapabilities: [], authorityClass: 'PROPOSE', preferredExecutionTargets: [], permittedExecutionTargets: ['LOCAL_ANDROID'],
    timeoutPolicy: { timeoutSeconds: null, onTimeout: 'FAIL_SAFE' }, retryPolicy: { maxRetries: 0, backoff: 'NONE' }, idempotencyMode: 'PURE',
    compensation: { compensable: false, nonCompensabilityAcknowledged: true }, verificationRequirements: { requiredForSuccess: false, verifierIds: [] },
    sideEffectClass: 'NONE', onFailureTerminalState: 'FAILED_SAFE', explanation: `Node ${id}.`,
    ...over, ...(cfg ? { config: cfg } : {}),
  };
}
export const passthrough = (id, over = {}) => node(id, 'DETERMINISTIC_TRANSFORMATION', { config: { passthrough: true }, ...over });

export function edge(id, from, to, label = null, gatewayId = null) { return { edgeId: id, fromNodeId: from, toNodeId: to, label, gatewayId }; }

export function baseDef(over = {}) {
  return {
    loopId: 'loop.test', semanticVersion: '1.0.0', objective: 'test objective', documentationRef: null,
    inputSchema: { location: 'INLINE', inlineSchema: { type: 'object' } }, outputSchema: { location: 'INLINE', inlineSchema: { type: 'string' } },
    startNodeId: 'start', terminalNodeIds: ['end'], nodes: [], edges: [], gateways: [],
    budgets: { steps: 1000, wallClockSeconds: 0, tokens: 100000000, cost: { amount: 1000, currency: 'USD' }, toolCalls: 1000 },
    bindingSlots: [], requiredCapabilities: [], executionTargetConstraints: { permittedTargetTypes: ['LOCAL_ANDROID'] }, distributionConstraints: {},
    minEngineVersion: '1.0.0', maxEngineVersion: null, verificationPolicy: { requireVerificationForSuccess: false, verifiers: [] },
    terminalStateMapping: { unverifiedSuccessAllowed: true, nodeFailureEscalations: [] },
    provenance: { sourceLocation: 'test', projectRevision: 'n/a', initiatingPrincipal: 'user:test' }, licenseRef: { spdxId: 'MIT' },
    ...over,
  };
}

/** start(passthrough) > t1 > ... > tN > end(passthrough): a linear graph with no gateways (Kotlin sequentialGraph). */
export function seqDef(steps = 3, { systemPrompt = '', over = {} } = {}) {
  const nodes = [passthrough('start')];
  for (let i = 1; i <= steps; i++) nodes.push(node(`t${i}`, 'MODEL_INFERENCE', { config: { name: `Task ${i}`, ...(systemPrompt ? { systemPrompt } : {}) } }));
  nodes.push(passthrough('end'));
  const edges = [edge('e0', 'start', 't1')];
  for (let i = 1; i < steps; i++) edges.push(edge(`e${i}`, `t${i}`, `t${i + 1}`));
  edges.push(edge(`e${steps}`, `t${steps}`, 'end'));
  return baseDef({ loopId: 'seq', nodes, edges, ...over });
}

/** start > prop > crit > [gate g1] approve > end / refine > prop  (Kotlin refineGraph). */
export function refineDef(over = {}) {
  return baseDef({
    loopId: 'refine',
    nodes: [
      passthrough('start'),
      node('prop', 'MODEL_INFERENCE', { config: { role: 'proposer', model: 'qwen', name: 'Proposer' } }),
      node('crit', 'MODEL_INFERENCE', { config: { role: 'critic', model: 'claude', name: 'Critic' } }),
      passthrough('end'),
    ],
    edges: [edge('e1', 'start', 'prop'), edge('e2', 'prop', 'crit'), edge('e4', 'crit', 'end', 'approve', 'g1'), edge('e5', 'crit', 'prop', 'refine', 'g1')],
    gateways: [{
      gatewayId: 'g1', exhaustive: true,
      outcomes: [
        { label: 'approve', conditionExpression: 'approved', priority: 1, targetNodeId: 'end' },
        { label: 'refine', conditionExpression: '!approved', priority: 2, targetNodeId: 'prop' },
      ],
    }],
    ...over,
  });
}

/** Executors for the refine loop: the critic approves on its 2nd pass; the proposer always proposes. */
export function refineExecutors() {
  let criticCalls = 0;
  return {
    crit: async () => (++criticCalls >= 2 ? 'APPROVE looks good' : 'needs work'),
    prop: async (n) => `proposal v${n.nodeId}`,
  };
}

export const allMock = (out = 'out') => ({ MODEL_INFERENCE: async () => out });
