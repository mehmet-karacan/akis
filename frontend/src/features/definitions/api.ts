import { ApiProblem, apiRequest, jsonBody } from '../../core/api/client'
import type {
  DataBinding,
  Definition,
  DefinitionType,
  DefinitionTypeDescriptor,
  DefinitionVersion,
  Draft,
  Folder,
  NewDefinitionInput,
  NewFolderInput,
  MoveDefinitionInput,
  MoveFolderInput,
  Scenario,
} from './types'

const base = '/api/v1'
const segment = encodeURIComponent

export const definitionsApi = {
  listTypes: () => apiRequest<DefinitionTypeDescriptor[]>(`${base}/definition-types`),

  listFolders: (projectUuid: string) =>
    apiRequest<Folder[]>(`${base}/projects/${segment(projectUuid)}/folders`),

  createFolder: (projectUuid: string, input: NewFolderInput) =>
    apiRequest<Folder>(`${base}/projects/${segment(projectUuid)}/folders`, {
      method: 'POST',
      ...jsonBody(input),
    }),

  moveFolder: (projectUuid: string, folderUuid: string, input: MoveFolderInput) =>
    apiRequest<Folder>(`${base}/projects/${segment(projectUuid)}/folders/${segment(folderUuid)}/move`, {
      method: 'POST',
      ...jsonBody(input),
    }),

  listDefinitions: (projectUuid: string, type?: DefinitionType) =>
    apiRequest<Definition[]>(
      `${base}/projects/${segment(projectUuid)}/definitions${type ? `?type=${segment(type)}` : ''}`,
    ),

  createDefinition: (projectUuid: string, input: NewDefinitionInput) =>
    apiRequest<Definition>(`${base}/projects/${segment(projectUuid)}/definitions`, {
      method: 'POST',
      ...jsonBody(input),
    }),

  moveDefinition: (projectUuid: string, definitionUuid: string, input: MoveDefinitionInput) =>
    apiRequest<Definition>(`${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/move`, {
      method: 'POST',
      ...jsonBody(input),
    }),

  async getDraft(projectUuid: string, definitionUuid: string): Promise<Draft | null> {
    try {
      return await apiRequest<Draft>(
        `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/draft`,
      )
    } catch (error) {
      if (error instanceof ApiProblem && error.status === 404) return null
      throw error
    }
  },

  saveDraft: (
    projectUuid: string,
    definitionUuid: string,
    expectedVersion: number,
    schemaVersion: number,
    content: unknown,
  ) =>
    apiRequest<Draft>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/draft`,
      {
        method: 'PUT',
        ...jsonBody({ expectedVersion, schemaVersion, content }),
      },
    ),

  listVersions: (projectUuid: string, definitionUuid: string) =>
    apiRequest<DefinitionVersion[]>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions`,
    ),

  createVersion: (
    projectUuid: string,
    definitionUuid: string,
    expectedDraftVersion: number,
    description: string,
  ) =>
    apiRequest<DefinitionVersion>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions`,
      {
        method: 'POST',
        ...jsonBody({ expectedDraftVersion, description: description || null }),
      },
    ),

  listScenarios: (projectUuid: string, definitionUuid: string, versionUuid: string) =>
    apiRequest<Scenario[]>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions/${segment(versionUuid)}/scenarios`,
    ),

  compileScenario: (projectUuid: string, definitionUuid: string, versionUuid: string) =>
    apiRequest<Scenario>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions/${segment(versionUuid)}/scenarios/compile`,
      { method: 'POST' },
    ),

  listBindings: (projectUuid: string, definitionUuid: string, versionUuid: string) =>
    apiRequest<DataBinding[]>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions/${segment(versionUuid)}/data-bindings`,
    ),

  createBinding: (
    projectUuid: string,
    definitionUuid: string,
    versionUuid: string,
    input: Pick<DataBinding, 'nodeCode' | 'role' | 'dataObjectUuid' | 'schemaSnapshotUuid'>,
  ) =>
    apiRequest<DataBinding>(
      `${base}/projects/${segment(projectUuid)}/definitions/${segment(definitionUuid)}/versions/${segment(versionUuid)}/data-bindings`,
      { method: 'POST', ...jsonBody(input) },
    ),
}
