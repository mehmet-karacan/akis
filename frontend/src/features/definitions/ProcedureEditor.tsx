import { TabBar } from '../../core/ui/TabBar'
import { DataGrid } from '../../core/ui/DataGrid'
import { Checkbox as AntCheckbox } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import { Input as AntInput } from 'antd'
import {
  ArrowDown,
  ArrowUp,
  Copy,
  Plus,
  Trash2,
  Undo2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { ProcedureSqlPanel } from "./ProcedureSqlPanel";
import {
  topologyApi,
  type Environment,
  type LogicalSchema,
} from "../topology/api";
import { useDefinitionsI18n } from "./i18n";
import { definitionsApi } from "./api";
import {
  normalizeProcedureLogCounter,
  PROCEDURE_LOG_COUNTERS,
} from "./procedureCatalog";
import type {
  ProcedureConnectionRole,
  ProcedureContent,
  ProcedureTask,
  Definition,
} from "./types";

interface Props {
  projectUuid: string;
  definition?: { code: string; name: string; description: string | null };
  value: ProcedureContent;
  onChange: (value: ProcedureContent) => void;
  limits?: {
    maximumTasks: number;
    maximumRowsetRows: number;
    maximumTimeoutSeconds: number;
  };
}
const PAGE_SIZE = 100;
const LOG_COUNTER_LABEL_KEYS = {
  NONE: "logCounterNONE",
  INSERT: "logCounterINSERT",
  UPDATE: "logCounterUPDATE",
  DELETE: "logCounterDELETE",
  ERRORS: "logCounterERRORS",
} as const;

export function pageProcedureTasks(
  tasks: ProcedureTask[],
  query: string,
  page: number,
  pageSize = PAGE_SIZE,
) {
  const normalized = query.trim().toLocaleLowerCase();
  const filtered = tasks
    .map((task, index) => ({ task, index }))
    .filter(
      ({ task }) =>
        !normalized ||
        `${task.name ?? ""} ${task.id} ${task.command}`
          .toLocaleLowerCase()
          .includes(normalized),
    );
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(Math.max(0, page), pages - 1);
  return {
    total: filtered.length,
    pages,
    page: safePage,
    items: filtered.slice(safePage * pageSize, (safePage + 1) * pageSize),
  };
}

function nextId(tasks: ProcedureTask[]) {
  let number = tasks.length + 1;
  while (tasks.some((task) => task.id === `STEP_${number}`)) number += 1;
  return `STEP_${number}`;
}
function nextTask(
  role: ProcedureConnectionRole,
  tasks: ProcedureTask[],
): ProcedureTask {
  const id = nextId(tasks);
  return {
    id,
    name: role === "SOURCE" ? "Read source" : "Target command",
    type: "SQL",
    connectionRole: role,
    riskClass: role === "SOURCE" ? "READ_ONLY" : "DML",
    command:
      role === "SOURCE"
        ? "SELECT * FROM SOURCE_TABLE"
        : "INSERT INTO TARGET_TABLE (ID) VALUES (:ID)",
    logCounter: role === "SOURCE" ? "NONE" : "INSERT",
    transactionMode: "AUTOCOMMIT",
    transactionIsolation: "DRIVER_DEFAULT",
    commitMode: "COMMIT",
    onError: "STOP",
  };
}

interface ProcedureStepUnit {
  tasks: ProcedureTask[];
  source?: ProcedureTask;
  target?: ProcedureTask;
  firstIndex: number;
}

export function groupProcedureTasks(tasks: ProcedureTask[]): ProcedureStepUnit[] {
  const units: ProcedureStepUnit[] = [];
  for (let index = 0; index < tasks.length; index += 1) {
    const task = tasks[index]!;
    const next = tasks[index + 1];
    if (
      task.connectionRole === "SOURCE" &&
      next?.connectionRole === "TARGET"
    ) {
      units.push({ tasks: [task, next], source: task, target: next, firstIndex: index });
      index += 1;
    } else {
      units.push({
        tasks: [task],
        source: task.connectionRole === "SOURCE" ? task : undefined,
        target: task.connectionRole === "TARGET" ? task : undefined,
        firstIndex: index,
      });
    }
  }
  return units;
}

export function inferProcedureTaskMetadata(task: ProcedureTask, command: string): Partial<ProcedureTask> {
  const sql = command.replace(/\/\*[\s\S]*?\*\/|--[^\r\n]*/g, ' ').trimStart().toLocaleUpperCase("en-US");
  if (task.connectionRole === "SOURCE" || /^SELECT\b/.test(sql)) {
    return { type: "SQL", riskClass: "READ_ONLY", requiresApproval: undefined };
  }
  if (/^(TRUNCATE|DROP|DELETE)\b/.test(sql)) {
    return { type: "SQL", riskClass: "DESTRUCTIVE", requiresApproval: true };
  }
  if (/^(BEGIN|DECLARE)\b/.test(sql)) {
    return { type: "PLSQL", riskClass: "DESTRUCTIVE", requiresApproval: true };
  }
  if (/^CALL\b/.test(sql)) {
    return { type: "STORED_PROCEDURE", riskClass: "DESTRUCTIVE", requiresApproval: true };
  }
  if (/^(CREATE|ALTER|COMMENT|GRANT|REVOKE)\b/.test(sql)) {
    return { type: "SQL", riskClass: "DDL", requiresApproval: true };
  }
  return { type: "SQL", riskClass: "DML", requiresApproval: undefined };
}

export function isProcedureSideConfigured(task?: ProcedureTask) {
  return Boolean(task?.logicalSchemaUuid && task.environmentUuid && task.command.trim());
}
export function applyAutomaticRowHandoffs(
  tasks: ProcedureTask[],
  maximumRows: number,
) {
  const normalized: ProcedureTask[] = tasks.map((task) => ({
    ...task,
    output: undefined,
    input: undefined,
  }));
  for (let index = 0; index < normalized.length - 1; index += 1) {
    const source = normalized[index]!;
    const target = normalized[index + 1]!;
    const producesRows =
      source.connectionRole === "SOURCE" &&
      source.type === "SQL" &&
      source.riskClass === "READ_ONLY";
    const consumesRows =
      target.connectionRole === "TARGET" &&
      target.type === "SQL" &&
      target.riskClass === "DML";
    if (producesRows && consumesRows) {
      source.output = { kind: "ROWSET", maxRows: maximumRows };
      target.input = {
        fromTask: source.id,
        mode: "BATCH",
        batchSize: Math.min(250, maximumRows),
      };
      index += 1;
    }
  }
  return normalized;
}

export function ProcedureEditor({
  projectUuid,
  value,
  onChange,
  limits = {
    maximumTasks: 1000,
    maximumRowsetRows: 1000,
    maximumTimeoutSeconds: 300,
  },
}: Props) {
  const { t } = useDefinitionsI18n();
  const [selectedTaskId, setSelectedTaskId] = useState(
    value.tasks[0]?.id ?? "",
  );
  const [selectedRole, setSelectedRole] = useState<ProcedureConnectionRole>("TARGET");
  const [selectedDetail, setSelectedDetail] = useState<"GENERAL" | ProcedureConnectionRole>("GENERAL");
  const [page, setPage] = useState(0);
  const [undoTasks, setUndoTasks] = useState<ProcedureTask[] | null>(null);
  const [pendingDelete, setPendingDelete] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [variables, setVariables] = useState<Definition[]>([]);
  useEffect(() => {
    let active = true;
    void Promise.all([
      topologyApi.listLogicalSchemas(projectUuid),
      topologyApi.listEnvironments(projectUuid),
    ])
      .then(([l, e]) => {
        if (active) {
          setLogicalSchemas(l);
          setEnvironments(e);
        }
      })
      .catch(() => {
        if (active) setMessage(t("contextLoadFailed"));
      });
    void definitionsApi.listDefinitions(projectUuid, "VARIABLE")
      .then((items) => { if (active) setVariables(items); })
      .catch(() => { if (active) setMessage(t("contextLoadFailed")); });
    return () => {
      active = false;
    };
  }, [projectUuid, t]);
  useEffect(() => {
    if (!value.tasks.some((task) => task.id === selectedTaskId))
      setSelectedTaskId(value.tasks[0]?.id ?? "");
  }, [selectedTaskId, value.tasks]);
  const replaceTasks = (tasks: ProcedureTask[], undo = false) => {
    if (undo) setUndoTasks(value.tasks);
    onChange({
      ...value,
      tasks: applyAutomaticRowHandoffs(tasks, limits.maximumRowsetRows),
    });
  };
  const update = (index: number, task: ProcedureTask) => {
    replaceTasks(
      value.tasks.map((current, position) =>
        position === index ? task : current,
      ),
    );
  };
  const add = () => {
    const task = nextTask("TARGET", value.tasks);
    replaceTasks([...value.tasks, task], true);
    setSelectedTaskId(task.id);
    setSelectedDetail("GENERAL");
    setPage(Math.floor(value.tasks.length / PAGE_SIZE));
  };
  const duplicate = (index: number) => {
    const source = value.tasks[index];
    if (!source) return;
    const unit = groupProcedureTasks(value.tasks).find((candidate) => candidate.tasks.includes(source));
    if (!unit) return;
    const reserved = [...value.tasks];
    const idMap = new Map<string, string>();
    unit.tasks.forEach((task) => {
      const id = nextId(reserved);
      idMap.set(task.id, id);
      reserved.push({ ...task, id });
    });
    const copies = unit.tasks.map((task) => ({
      ...structuredClone(task),
      id: idMap.get(task.id)!,
      name: `${task.name || task.id} (${t("copy")})`,
      input: task.input ? { ...task.input, fromTask: idMap.get(task.input.fromTask) ?? task.input.fromTask } : undefined,
    }));
    const tasks = [...value.tasks];
    tasks.splice(unit.firstIndex + unit.tasks.length, 0, ...copies);
    replaceTasks(tasks, true);
    setSelectedTaskId(copies.at(-1)!.id);
  };
  const remove = (index: number) => {
    const source = value.tasks[index];
    if (!source) return;
    const unit = groupProcedureTasks(value.tasks).find((candidate) => candidate.tasks.includes(source));
    if (!unit) return;
    const removedIds = new Set(unit.tasks.map((task) => task.id));
    const dependents = value.tasks.filter(
      (task) => !removedIds.has(task.id) && task.input?.fromTask && removedIds.has(task.input.fromTask),
    );
    if (dependents.length && pendingDelete !== index) {
      setPendingDelete(index);
      setMessage(t("deleteStepImpact", { count: dependents.length }));
      return;
    }
    const tasks = value.tasks
      .filter((task) => !removedIds.has(task.id))
      .map((task) =>
        task.input?.fromTask && removedIds.has(task.input.fromTask)
          ? { ...task, input: undefined }
          : task,
      );
    replaceTasks(tasks, true);
    setPendingDelete(null);
    setMessage("");
    setSelectedTaskId(tasks[Math.min(unit.firstIndex, tasks.length - 1)]?.id ?? "");
  };
  const move = (index: number, offset: number) => {
    const selected = value.tasks[index];
    if (!selected) return;
    const units: ProcedureTask[][] = [];
    for (let i = 0; i < value.tasks.length; i += 1) {
      const current = value.tasks[i]!;
      const next = value.tasks[i + 1];
      if (current.output && next?.input?.fromTask === current.id) {
        units.push([current, next]);
        i += 1;
      } else units.push([current]);
    }
    const unit = units.findIndex((entry) => entry.includes(selected));
    const target = unit + offset;
    if (target < 0 || target >= units.length) return;
    [units[unit], units[target]] = [units[target]!, units[unit]!];
    replaceTasks(units.flat(), true);
  };
  const units = useMemo(() => groupProcedureTasks(value.tasks), [value.tasks]);
  const pages = Math.max(1, Math.ceil(units.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), pages - 1);
  const visible = units.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);
  const selectedUnit = units.find((unit) => unit.tasks.some((task) => task.id === selectedTaskId));
  const updateTask = (task: ProcedureTask, patch: Partial<ProcedureTask>) => {
    const index = value.tasks.findIndex((candidate) => candidate.id === task.id);
    if (index >= 0) update(index, { ...task, ...patch });
  };
  const updateSide = (role: ProcedureConnectionRole, task: ProcedureTask | undefined, patch: Partial<ProcedureTask>) => {
    if (task) {
      updateTask(task, patch);
      return;
    }
    if (!selectedUnit) return;
    const created = {
      ...nextTask(role, value.tasks),
      name: (selectedUnit.target ?? selectedUnit.source)?.name ?? t("newStep"),
      command: "",
      ...patch,
    };
    const tasks = [...value.tasks];
    tasks.splice(role === "SOURCE" ? selectedUnit.firstIndex : selectedUnit.firstIndex + selectedUnit.tasks.length, 0, created);
    replaceTasks(tasks, true);
    setSelectedTaskId(created.id);
  };
  const renderTaskSide = (role: ProcedureConnectionRole, task?: ProcedureTask) => {
    return (
      <section className={`procedure-side procedure-side--${role.toLowerCase()}`}>
        <div className="procedure-side-context">
            <label>
              <span>{t("logicalSchema")}</span>
              <FormSelect value={task?.logicalSchemaUuid ?? ""} onChange={(event) => updateSide(role, task, { logicalSchemaUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </FormSelect>
            </label>
            <label>
              <span>{t("environment")}</span>
              <FormSelect value={task?.environmentUuid ?? ""} onChange={(event) => updateSide(role, task, { environmentUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </FormSelect>
            </label>
            {role === "TARGET" ? <>
              <label>
                <span>{t("transactionMode")}</span>
                <FormSelect value={task?.transactionMode ?? "AUTOCOMMIT"} onChange={(event) => updateSide(role, task, {
                  transactionMode: event.target.value as ProcedureTask["transactionMode"],
                  transactionChannel: event.target.value === "TRANSACTION" ? task?.transactionChannel ?? 0 : undefined,
                  commitMode: event.target.value === "TRANSACTION" ? task?.commitMode ?? "NO_COMMIT" : "COMMIT",
                })}>
                  <option value="AUTOCOMMIT">{t("autocommit")}</option>
                  {task?.riskClass === "DML" ? <option value="TRANSACTION">{t("managedTransaction")}</option> : null}
                </FormSelect>
              </label>
              {(task?.transactionMode ?? "AUTOCOMMIT") === "TRANSACTION" ? <label>
                <span>{t("transactionChannel")}</span>
                <FormSelect value={task?.transactionChannel ?? 0} onChange={(event) => updateSide(role, task, { transactionChannel: Number(event.target.value) })}>
                  {Array.from({ length: 10 }, (_, channel) => <option key={channel} value={channel}>{t("transactionChannelValue", { channel })}</option>)}
                </FormSelect>
              </label> : null}
            </> : null}
            <label>
              <span>{t("transactionIsolation")}</span>
              <FormSelect value={task?.transactionIsolation ?? "DRIVER_DEFAULT"} onChange={(event) => updateSide(role, task, { transactionIsolation: event.target.value as ProcedureTask["transactionIsolation"] })}>
                <option value="DRIVER_DEFAULT">{t("driverDefault")}</option>
                <option value="READ_COMMITTED">Read Committed</option>
                <option value="SERIALIZABLE">Serializable</option>
              </FormSelect>
            </label>
            {role === "TARGET" && (task?.transactionMode ?? "AUTOCOMMIT") === "TRANSACTION" ? <label>
              <span>{t("commitMode")}</span>
              <FormSelect value={task?.commitMode ?? "NO_COMMIT"} onChange={(event) => updateSide(role, task, { commitMode: event.target.value as ProcedureTask["commitMode"] })}>
                <option value="NO_COMMIT">{t("noCommit")}</option>
                <option value="COMMIT">{t("commit")}</option>
              </FormSelect>
            </label> : null}
          </div>
          <ProcedureSqlPanel key={`${role}:${task?.id ?? 'new'}`} projectUuid={projectUuid} role={role} task={task} variables={variables} onApply={(command, parameters) => {
            const base = task ?? nextTask(role, value.tasks);
            const metadata = inferProcedureTaskMetadata(base, command);
            updateSide(role, task, { command, parameters, ...metadata,
              ...(role === "TARGET" && metadata.riskClass !== "DML" ? { transactionMode: "AUTOCOMMIT" as const, transactionChannel: undefined, commitMode: "COMMIT" as const } : {}),
            });
          }} />
      </section>
    );
  };
  return (
    <div className="procedure-editor">
      <header className="procedure-editor-heading">
        <div>
          <h3>{t("procedureSteps")}</h3>
          <p>{t("procedureHint")}</p>
        </div>
        <div className="mapping-inline-actions">
          <AntActionButton
            tone="secondary"
            type="button"
            disabled={!undoTasks}
            onClick={() => {
              if (undoTasks) {
                onChange({ ...value, tasks: undoTasks });
                setUndoTasks(null);
              }
            }}
          >
            <Undo2 size={15} />
            {t("undo")}
          </AntActionButton>
          <AntActionButton
            tone="secondary"
            type="button"
            onClick={add}
          >
            <Plus size={15} />
            {t("addStep")}
          </AntActionButton>
        </div>
      </header>
      {message ? (
        <div className="procedure-reorder-error" role="alert">
          {message}
          {pendingDelete != null ? (
            <span>
              <AntActionButton tone="ghost" type="button" onClick={() => remove(pendingDelete)}>
                {t("confirmRemove")}
              </AntActionButton>
              <AntActionButton tone="ghost"
                type="button"
                onClick={() => {
                  setPendingDelete(null);
                  setMessage("");
                }}
              >
                {t("cancel")}
              </AntActionButton>
            </span>
          ) : null}
        </div>
      ) : null}
      <div className="procedure-workbench">
        <aside className="procedure-list-column">
          <div className="procedure-task-list">
          <DataGrid viewControls={false} className="procedure-task-table" aria-label={t("procedureSteps")}>
            <colgroup><col className="procedure-col-number" /><col /><col className="procedure-col-command" /><col className="procedure-col-counter" /><col className="procedure-col-actions" /></colgroup>
            <thead><tr><th>#</th><th>{t("stepName")}</th><th>{t("commands")}</th><th>{t("logCounter")}</th><th>{t("actions")}</th></tr></thead>
            <tbody>
            {visible.map((unit, pageIndex) => {
              const index = safePage * PAGE_SIZE + pageIndex;
              const task = unit.target ?? unit.source!;
              const isSelected = unit.tasks.some((candidate) => candidate.id === selectedTaskId);
              return (
              <tr
                className={`procedure-task ${isSelected ? "is-selected" : ""}`}
                key={task.id}
              >
                <td className="procedure-step-number">{index + 1}</td>
                <td><AntActionButton tone="ghost" className="procedure-task-select" type="button" onClick={() => { setSelectedTaskId(task.id); setSelectedRole(unit.target ? "TARGET" : "SOURCE"); }} aria-pressed={isSelected}><strong>{task.name || task.id}</strong></AntActionButton></td>
                <td><small className="procedure-step-route"><span className={isProcedureSideConfigured(unit.source) ? "is-ready" : ""}>{t("source")}</span><span aria-hidden="true">→</span><span className={isProcedureSideConfigured(unit.target) ? "is-ready" : ""}>{t("target")}</span></small></td>
                <td><small className="procedure-step-counter">{t(LOG_COUNTER_LABEL_KEYS[normalizeProcedureLogCounter((unit.target ?? unit.source)?.logCounter)])}</small></td>
                <td><div className="procedure-task-actions">
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveUp")}: ${task.name || task.id}`}
                    disabled={index === 0}
                    onClick={() => move(unit.firstIndex, -1)}
                  >
                    <ArrowUp size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveDown")}: ${task.name || task.id}`}
                    disabled={index === units.length - 1}
                    onClick={() => move(unit.firstIndex, 1)}
                  >
                    <ArrowDown size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("duplicate")}: ${task.name || task.id}`}
                    onClick={() => duplicate(unit.firstIndex)}
                  >
                    <Copy size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("remove")}: ${task.name || task.id}`}
                    onClick={() => remove(unit.firstIndex)}
                  >
                    <Trash2 size={15} />
                  </AntActionButton>
                </div></td>
              </tr>
              );
            })}
            </tbody>
          </DataGrid>
          </div>
          {pages > 1 ? (
            <nav className="procedure-pagination" aria-label={t("stepPages")}>
              <AntActionButton tone="ghost"
                type="button"
                disabled={safePage === 0}
                onClick={() => setPage((current) => current - 1)}
              >
                {t("previous")}
              </AntActionButton>
              <span>{t("page", { page: safePage + 1, pages })}</span>
              <AntActionButton tone="ghost"
                type="button"
                disabled={safePage >= pages - 1}
                onClick={() => setPage((current) => current + 1)}
              >
                {t("next")}
              </AntActionButton>
            </nav>
          ) : null}
        </aside>
        {selectedUnit ? (
          <section
            className="procedure-task-editor"
            aria-label={`${t("stepEditor")}: ${(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}`}
          >
            <header>
              <div><strong>{t("stepDetails")}</strong><span aria-hidden="true">·</span><b>{(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}</b></div>
              <span>
                {units.indexOf(selectedUnit) + 1} / {units.length}
              </span>
            </header>
            <TabBar className="procedure-command-tabs" role="tablist" aria-label={t("commands")}>
              <AntActionButton tone="ghost" type="button" role="tab" aria-selected={selectedDetail === "GENERAL"} onClick={() => setSelectedDetail("GENERAL")}>{t("general")}</AntActionButton>
              <AntActionButton tone="ghost" type="button" role="tab" aria-selected={selectedDetail === "TARGET"} onClick={() => { setSelectedRole("TARGET"); setSelectedDetail("TARGET"); }}>{t("targetCommand")}</AntActionButton>
              <AntActionButton tone="ghost" type="button" role="tab" aria-selected={selectedDetail === "SOURCE"} onClick={() => { setSelectedRole("SOURCE"); setSelectedDetail("SOURCE"); }}>{t("sourceCommand")}</AntActionButton>
            </TabBar>
            <div className="procedure-command-detail">
              {selectedDetail === "GENERAL" ? (() => {
                const primary = selectedUnit.target ?? selectedUnit.source!;
                return <section className="procedure-general-properties">
                  <label className="procedure-general-name"><span>{t("stepName")}</span><AntInput aria-label={t("stepName")} value={primary.name ?? ""} onChange={(event) => {
                    const ids = new Set(selectedUnit.tasks.map((task) => task.id));
                    replaceTasks(value.tasks.map((task) => ids.has(task.id) ? { ...task, name: event.target.value } : task));
                  }} /></label>
                  <label><span>{t("logCounter")}</span><FormSelect aria-label={t("logCounter")} value={normalizeProcedureLogCounter(primary.logCounter)} onChange={(event) => updateTask(primary, { logCounter: event.target.value as ProcedureTask["logCounter"] })}>{PROCEDURE_LOG_COUNTERS.map((counter) => <option key={counter} value={counter}>{t(LOG_COUNTER_LABEL_KEYS[counter])}</option>)}</FormSelect></label>
                  <label className="procedure-checkbox"><AntCheckbox  checked={primary.onError === "CONTINUE"} disabled={Boolean(primary.input || primary.output)} onChange={(event) => updateTask(primary, { onError: event.target.checked ? "CONTINUE" : "STOP" })} /><span>{t("ignoreErrors")}</span></label>
                  {primary.input || primary.output ? <p>{t("rowTransferStopsOnError")}</p> : null}
                </section>;
              })() : renderTaskSide(selectedRole, selectedRole === "SOURCE" ? selectedUnit.source : selectedUnit.target)}
            </div>
          </section>
        ) : (
          <p className="definition-state">{t("noProcedureSteps")}</p>
        )}
      </div>
    </div>
  );
}
