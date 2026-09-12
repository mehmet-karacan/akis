const lastProjectKey = 'akis.lastProjectUuid'

export function rememberProject(projectUuid: string) {
  localStorage.setItem(lastProjectKey, projectUuid)
  window.dispatchEvent(new CustomEvent('akis:project-changed', { detail: projectUuid }))
}

export function getRememberedProject() {
  return localStorage.getItem(lastProjectKey)
}
