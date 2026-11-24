/* LoopConfig — the editable contents of the canonical refine loop (objective, proposer, critic, iterations), mapped
   to and from a LoopDefinition v2. Port of Kotlin LoopConfig / LoopConfigBpmn (which maps to a BPMN graph; here the
   target is the v2 definition and the carrier is JSON, BPMN XML being deferred — see README).

   Shape produced: start(passthrough) > proposer(MODEL_INFERENCE) > critic(MODEL_INFERENCE) > gateway g1
     g1: 'approve' (condition `approved`, priority 1) > end(passthrough)   |   'refine' (default) > proposer
   `iterations` bounds the cycle: budgets.steps = iterations * 2 (one proposer + one critic step per round). */

export function expert(role, name, systemPrompt, { modelId = null, x = 0, y = 0 } = {}) { return { role, name, systemPrompt, modelId, x, y }; }

function modelNode(id, cfg, extraConfig = {}) {
  return {
    nodeId: id, category: 'MODEL_INFERENCE', inputPorts: [{ portId: 'in', mediaType: 'text/plain' }], outputPorts: [{ portId: 'out', mediaType: 'text/plain' }],
    implementation: { kind: 'TYPED_SLOT', slotId: `${id}-model` }, requestedCapabilities: [], authorityClass: 'PROPOSE',
    preferredExecutionTargets: ['LOCAL_ANDROID'], permittedExecutionTargets: ['LOCAL_ANDROID'],
    timeoutPolicy: { timeoutSeconds: null, onTimeout: 'FAIL_SAFE' }, retryPolicy: { maxRetries: 0, backoff: 'NONE' }, idempotencyMode: 'PURE',
    compensation: { compensable: false, nonCompensabilityAcknowledged: true }, verificationRequirements: { requiredForSuccess: false, verifierIds: [] },
    sideEffectClass: 'NONE', onFailureTerminalState: 'FAILED_SAFE', explanation: cfg.name || id,
    config: { role: cfg.role, name: cfg.name, systemPrompt: cfg.systemPrompt, ...(cfg.modelId ? { model: cfg.modelId } : {}), ...extraConfig },
    presentation: { x: cfg.x, y: cfg.y },
  };
}
function marker(id, label, x, y, extra = {}) {
  return {
    nodeId: id, category: 'DETERMINISTIC_TRANSFORMATION', inputPorts: [], outputPorts: [], implementation: { kind: 'TYPED_SLOT', slotId: 'marker' },
    requestedCapabilities: [], authorityClass: 'OBSERVE', preferredExecutionTargets: [], permittedExecutionTargets: ['LOCAL_ANDROID'],
    timeoutPolicy: { timeoutSeconds: null, onTimeout: 'FAIL_SAFE' }, retryPolicy: { maxRetries: 0, backoff: 'NONE' }, idempotencyMode: 'PURE',
    compensation: { compensable: false, nonCompensabilityAcknowledged: true }, verificationRequirements: { requiredForSuccess: false, verifierIds: [] },
    sideEffectClass: 'NONE', onFailureTerminalState: 'FAILED_SAFE', explanation: label, config: { passthrough: true, ...extra }, presentation: { x, y },
  };
}

export function loopConfigToDefinition(c) {
  const iterations = c.iterations ?? 2;
  return {
    loopId: c.id, semanticVersion: '1.0.0', objective: c.objective || c.name || c.id, documentationRef: null,
    inputSchema: { location: 'INLINE', inlineSchema: { type: 'object', properties: {} } },
    outputSchema: { location: 'INLINE', inlineSchema: { type: 'string' } },
    startNodeId: 'start', terminalNodeIds: ['end'],
    nodes: [marker('start', 'Start', 40, 140, { objective: c.objective }), modelNode('proposer', c.proposer), modelNode('critic', c.critic, { iterations }), marker('end', 'End', 560, 240)],
    edges: [
      { edgeId: 'e1', fromNodeId: 'start', toNodeId: 'proposer', label: null, gatewayId: null },
      { edgeId: 'e2', fromNodeId: 'proposer', toNodeId: 'critic', label: null, gatewayId: null },
      { edgeId: 'e3', fromNodeId: 'critic', toNodeId: 'proposer', label: 'refine', gatewayId: 'g1' },
      { edgeId: 'e4', fromNodeId: 'critic', toNodeId: 'end', label: 'approve', gatewayId: 'g1' },
    ],
    gateways: [{
      gatewayId: 'g1', exhaustive: false,
      outcomes: [{ label: 'approve', conditionExpression: 'approved', priority: 1, targetNodeId: 'end' }],
      defaultOrTerminalFailurePath: { label: 'refine', conditionExpression: null, priority: null, targetNodeId: 'proposer' },
    }],
    budgets: { steps: Math.max(2, iterations * 2), wallClockSeconds: 600, tokens: 200000, cost: { amount: 5, currency: 'USD' }, toolCalls: 0 },
    bindingSlots: [{ slotId: 'proposer-model', kind: 'MODEL', required: false }, { slotId: 'critic-model', kind: 'MODEL', required: false }],
    requiredCapabilities: [], executionTargetConstraints: { permittedTargetTypes: ['LOCAL_ANDROID'], preferredTargetTypes: ['LOCAL_ANDROID'], requiresAlwaysOnLocal: false },
    distributionConstraints: {}, minEngineVersion: '1.0.0', maxEngineVersion: null,
    verificationPolicy: { requireVerificationForSuccess: false, verifiers: [] }, terminalStateMapping: { unverifiedSuccessAllowed: true, nodeFailureEscalations: [] },
    provenance: { sourceLocation: 'loop-config', projectRevision: 'n/a', initiatingPrincipal: 'user:local' }, licenseRef: { spdxId: 'NOASSERTION' },
    name: c.name,
  };
}

export function definitionToLoopConfig(def) {
  const get = (id) => { const n = def.nodes.find((x) => x.nodeId === id); if (!n) throw new Error(`definition has no ${id} node`); return n; };
  const ex = (n) => ({ role: n.config?.role ?? '', name: n.config?.name ?? '', systemPrompt: n.config?.systemPrompt ?? '', modelId: n.config?.model ?? null, x: n.presentation?.x ?? 0, y: n.presentation?.y ?? 0 });
  const critic = get('critic');
  return { id: def.loopId, name: def.name ?? '', objective: def.nodes.find((n) => n.nodeId === 'start')?.config?.objective ?? '', proposer: ex(get('proposer')), critic: ex(critic), iterations: critic.config?.iterations ?? 2 };
}
