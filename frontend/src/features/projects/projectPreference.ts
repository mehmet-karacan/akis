const lastProjectKey = 'akis.lastProjectUuid'

export function rememberProject(projectUuid: string) {
  localStorage.setItem(lastProjectKey, projectUuid)
}

export function getRememberedProject() {
  return localStorage.getItem(lastProjectKey)
}
