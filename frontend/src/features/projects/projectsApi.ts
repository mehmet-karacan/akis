import { apiRequest, jsonBody } from '../../core/api/client'

export interface Project {
  uuid: string
  code: string
  status: string
  name: string
  description?: string | null
  version: number
  createdAt: string
}

export interface CreateProjectInput {
  code: string
  name: string
  description?: string
}

export const listProjects = () => apiRequest<Project[]>('/api/v1/projects')
export const getProject = (uuid: string) => apiRequest<Project>(`/api/v1/projects/${uuid}`)
export const createProject = (input: CreateProjectInput) => apiRequest<Project>('/api/v1/projects', {
  method: 'POST',
  ...jsonBody(input),
})
