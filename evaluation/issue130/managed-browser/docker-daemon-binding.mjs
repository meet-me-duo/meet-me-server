const CANONICAL_MANAGED_DOCKER = 'unix:///var/run/docker.sock';
export function validateManagedDockerBinding(selectedEndpoint, backendEndpoint) {
  if (selectedEndpoint !== CANONICAL_MANAGED_DOCKER || backendEndpoint !== CANONICAL_MANAGED_DOCKER) {
    const error = new Error('DOCKER_DAEMON_BINDING_REQUIRED');
    error.code = 'DOCKER_DAEMON_BINDING_REQUIRED';
    throw error;
  }
  return { bound: true };
}
