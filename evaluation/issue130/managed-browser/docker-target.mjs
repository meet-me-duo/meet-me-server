function permittedEndpoint(value) {
  if (value === 'unix:///var/run/docker.sock' || value === 'unix:///run/docker.sock') return true;
  if (typeof value !== 'string') return false;
  // Check the literal authority before URL parsing can normalize encoded or shorthand hosts.
  const match = /^(tcp|http|https):\/\/(localhost|127\.0\.0\.1|\[::1\])(?::([0-9]{1,5}))?\/?$/.exec(value);
  return Boolean(match && (!match[3] || (Number(match[3]) > 0 && Number(match[3]) <= 65535)));
}
export function validateLocalDockerTarget(evidence) {
  const valid = evidence !== null && typeof evidence === 'object' && !Array.isArray(evidence)
    && ['environmentHost', 'environmentContext', 'tlsVerify', 'certificatePath']
      .every(key => evidence[key] === null || typeof evidence[key] === 'string')
    && (evidence.environmentHost === null || evidence.environmentHost === '' || permittedEndpoint(evidence.environmentHost))
    && permittedEndpoint(evidence.contextEndpoint)
    && (evidence.tlsVerify === null || evidence.tlsVerify === '')
    && (evidence.certificatePath === null || evidence.certificatePath === '');
  if (!valid) { const error = new Error('LOCAL_DOCKER_REQUIRED'); error.code = 'LOCAL_DOCKER_REQUIRED'; throw error; }
  return { local: true };
}
