import { DataGrid } from '../../core/ui/DataGrid'
import { Checkbox as AntCheckbox, Splitter } from 'antd'
import { Select as FormSelect } from '../../core/ui/Select'
import { Button as AntActionButton } from '../../core/ui/Button'
import './procedure-editor.css'
import { Input as AntInput } from 'antd'
import { databaseProviderVisual } from '../topology/DatabaseProviderIcon'
import { ListOrdered, SlidersHorizontal, Database, DatabaseZap, SquarePen, Search, Cpu,
  ArrowDown,
  ArrowUp,
  Copy,
  Plus,
  Trash2,
  Undo2,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
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
    enabled: true,
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
  const workbenchRef = useRef<HTMLDivElement>(null);
  const [workbenchHeight, setWorkbenchHeight] = useState(0);
  const [resizedSteps, setResizedSteps] = useState<number | null>(null);
  useEffect(() => {
    const splitter = workbenchRef.current?.firstElementChild;
    if (!splitter) return;
    const observer = new ResizeObserver(() => setWorkbenchHeight(splitter.clientHeight));
    observer.observe(splitter);
    return () => observer.disconnect();
  }, []);
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
  const [stepFilter, setStepFilter] = useState("");
  const units = useMemo(() => groupProcedureTasks(value.tasks), [value.tasks]);
  const pages = Math.max(1, Math.ceil(units.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(0, page), pages - 1);
  const visible = units.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)
    .filter((unit) => !stepFilter.trim() || ((unit.target ?? unit.source)?.name ?? "").toLocaleLowerCase().includes(stepFilter.trim().toLocaleLowerCase()));
  const commandPreview = (task?: ProcedureTask) => task?.command ? task.command.replace(/\s+/g, " ").trim().slice(0, 48) : "";
  const roleTechnology = (role: ProcedureConnectionRole) => (role === "SOURCE" ? value.technology?.source : value.technology?.target)
    ?? value.tasks.filter((task) => task.connectionRole === role).map((task) => logicalSchemas.find((item) => item.uuid === task.logicalSchemaUuid)?.databaseType).find((type): type is string => Boolean(type))
    ?? "";
  const schemasFor = (role: ProcedureConnectionRole) => { const technology = roleTechnology(role); return technology ? logicalSchemas.filter((item) => item.databaseType === technology) : logicalSchemas; };
  const schemaNameOf = (task?: ProcedureTask) => logicalSchemas.find((item) => item.uuid === task?.logicalSchemaUuid)?.name ?? "";
  const technologyOf = (task?: ProcedureTask) => { const schema = logicalSchemas.find((item) => item.uuid === task?.logicalSchemaUuid); const type = schema?.databaseType ?? (task ? roleTechnology(task.connectionRole) : ""); return type ? databaseProviderVisual(type).label : ""; };
  const unitEnabled = (unit: ProcedureStepUnit) => unit.tasks.every((task) => task.enabled !== false);
  const setUnitFlag = (unit: ProcedureStepUnit, patch: Partial<ProcedureTask>) => { const ids = new Set(unit.tasks.map((task) => task.id)); replaceTasks(value.tasks.map((task) => ids.has(task.id) ? { ...task, ...patch } : task)); };
  const selectedUnit = units.find((unit) => unit.tasks.some((task) => task.id === selectedTaskId));
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const list = workbenchRef.current?.querySelector<HTMLElement>('.procedure-task-list');
      const row = list?.querySelector<HTMLElement>('tr.is-selected');
      if (!list || !row) return;
      const bounds = list.getBoundingClientRect();
      const selected = row.getBoundingClientRect();
      const header = list.querySelector('thead')?.getBoundingClientRect().height ?? 0;
      if (selected.bottom > bounds.bottom) list.scrollTop += selected.bottom - bounds.bottom;
      else if (selected.top < bounds.top + header) list.scrollTop -= bounds.top + header - selected.top;
    });
    return () => cancelAnimationFrame(frame);
  }, [selectedTaskId, workbenchHeight, resizedSteps]);
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
              <span>{t("targetTechnology").replace(/^Target |^Hedef /, "")}</span>
              <span className="procedure-technology" title={t("technologyFromDefinition")}><Cpu size={14} aria-hidden="true" />{roleTechnology(role) ? databaseProviderVisual(roleTechnology(role)).label : (technologyOf(task) || t("notSelected"))}</span>
            </label>
            <label>
              <span>{t("transactionIsolation")}</span>
              <FormSelect value={task?.transactionIsolation ?? "DRIVER_DEFAULT"} onChange={(event) => updateSide(role, task, { transactionIsolation: event.target.value as ProcedureTask["transactionIsolation"] })}>
                <option value="DRIVER_DEFAULT">{t("driverDefault")}</option>
                <option value="READ_COMMITTED">Read Committed</option>
                <option value="SERIALIZABLE">Serializable</option>
              </FormSelect>
            </label>
            <label>
              <span>{t("environment")}</span>
              <FormSelect value={task?.environmentUuid ?? ""} onChange={(event) => updateSide(role, task, { environmentUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </FormSelect>
            </label>
            <label>
              <span>{t("logicalSchema")}</span>
              <FormSelect value={task?.logicalSchemaUuid ?? ""} onChange={(event) => updateSide(role, task, { logicalSchemaUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {schemasFor(role).map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </FormSelect>
            </label>
            {role === "SOURCE" ? <>
              <label>
                <span>{t("transactionMode")}</span>
                <FormSelect value="AUTOCOMMIT" disabled aria-label={t("transactionMode")}><option value="AUTOCOMMIT">{t("autocommit")}</option></FormSelect>
              </label>
              <label>
                <span>{t("commitMode")}</span>
                <FormSelect value="" disabled aria-label={t("commitMode")}><option value="">{t("notSelected")}</option></FormSelect>
              </label>
            </> : null}
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
              {(task?.transactionMode ?? "AUTOCOMMIT") === "TRANSACTION" ? <label>
                <span>{t("commitMode")}</span>
                <FormSelect value={task?.commitMode ?? "NO_COMMIT"} onChange={(event) => updateSide(role, task, { commitMode: event.target.value as ProcedureTask["commitMode"] })}>
                  <option value="NO_COMMIT">{t("noCommit")}</option>
                  <option value="COMMIT">{t("commit")}</option>
                </FormSelect>
              </label> : <label>
                <span>{t("commitMode")}</span>
                <FormSelect value="" disabled aria-label={t("commitMode")}><option value="">{t("notSelected")}</option></FormSelect>
              </label>}
            </> : null}
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
    <div className="procedure-editor" ref={workbenchRef}>
      <Splitter className="procedure-workbench-split" orientation="vertical" onResize={sizes => setResizedSteps(sizes[0] ?? null)} onDraggerDoubleClick={() => setResizedSteps(null)}>
      <Splitter.Panel size={Math.max(152, Math.min(resizedSteps ?? Math.min(360, 176 + units.length * 44), (workbenchHeight || 520) - (resizedSteps == null ? 360 : 220)))} min={152} max="60%">
      <section className="procedure-steps-panel" aria-label={t("procedureSteps")}>
      <header className="procedure-editor-heading">
        <div className="procedure-heading-identity">
          <span className="procedure-heading-icon procedure-heading-icon--steps" aria-hidden="true"><ListOrdered size={18} /></span>
          <div>
            <h3>{t("procedureSteps")}<span className="procedure-heading-count" aria-hidden="true">{units.length}</span></h3>
            <p>{t("procedureHint")}</p>
          </div>
        </div>
        <div className="mapping-inline-actions procedure-toolbar">
          <label className="procedure-step-search"><Search size={14} aria-hidden="true" /><AntInput aria-label={t("searchSteps")} placeholder={t("searchSteps")} value={stepFilter} onChange={(event) => setStepFilter(event.target.value)} allowClear /></label>
          <AntActionButton tone="secondary" type="button" className="procedure-undo-button" disabled={!undoTasks}
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
          <AntActionButton tone="primary" type="button" onClick={add}><Plus size={15} />{t("addStep")}</AntActionButton>
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
        <aside className="procedure-list-column">
          <div className="procedure-task-list">
          <DataGrid viewControls={false} className="procedure-task-table" aria-label={t("procedureSteps")}>
            <colgroup><col className="procedure-col-number" /><col className="procedure-col-name" /><col className="procedure-col-flag" /><col className="procedure-col-flag" /><col className="procedure-col-command" /><col className="procedure-col-tech" /><col className="procedure-col-command" /><col className="procedure-col-tech" /><col className="procedure-col-tech" /><col className="procedure-col-tech" /><col className="procedure-col-tech" /><col className="procedure-col-counter" /><col className="procedure-col-actions" /></colgroup>
            <thead><tr><th>#</th><th>{t("stepName")}</th><th>{t("ignoreErrors")}</th><th>{t("execute")}</th><th>{t("targetCommand")}</th><th>{t("targetLogicalSchema")}</th><th>{t("sourceCommand")}</th><th>{t("sourceLogicalSchema")}</th><th>{t("targetTechnology")}</th><th>{t("targetTransaction")}</th><th>{t("targetCommit")}</th><th>{t("logCounter")}</th><th>{t("actions")}</th></tr></thead>
            <tbody>
            {visible.map((unit, pageIndex) => {
              const index = safePage * PAGE_SIZE + pageIndex;
              const task = unit.target ?? unit.source!;
              const isSelected = unit.tasks.some((candidate) => candidate.id === selectedTaskId);
              return (
              <tr
                className={`procedure-task ${isSelected ? "is-selected" : ""} ${unitEnabled(unit) ? "" : "is-disabled"}`}
                key={task.id}
                onClick={() => { setSelectedTaskId(task.id); if (selectedDetail !== "GENERAL") setSelectedRole(selectedDetail); }}
              >
                <td className="procedure-step-number"><span className="procedure-step-badge">{index + 1}</span></td>
                <td><AntActionButton tone="ghost" className="procedure-task-select" type="button" onClick={() => { setSelectedTaskId(task.id); if (selectedDetail !== "GENERAL") setSelectedRole(selectedDetail); }} aria-pressed={isSelected}><strong>{task.name || task.id}</strong></AntActionButton></td>
                <td className="procedure-flag-cell" title={task.input || task.output ? t("rowTransferStopsOnError") : undefined}><AntCheckbox aria-label={`${t("ignoreErrors")}: ${task.name || task.id}`} checked={task.onError === "CONTINUE"} disabled={Boolean(task.input || task.output)} onClick={(event) => event.stopPropagation()} onChange={(event) => setUnitFlag(unit, { onError: event.target.checked ? "CONTINUE" : "STOP" })} /></td>
                <td className="procedure-flag-cell"><AntCheckbox aria-label={`${t("execute")}: ${task.name || task.id}`} checked={unitEnabled(unit)} onClick={(event) => event.stopPropagation()} onChange={(event) => setUnitFlag(unit, { enabled: event.target.checked })} /></td>
                <td><span className={`procedure-command-cell procedure-route-chip procedure-route-chip--target ${isProcedureSideConfigured(unit.target) ? "is-ready" : ""}`}><Database size={12} aria-hidden="true" /><code>{commandPreview(unit.target) || "—"}</code></span></td>
                <td>{schemaNameOf(unit.target) || <span className="procedure-muted">—</span>}</td>
                <td><span className={`procedure-command-cell procedure-route-chip procedure-route-chip--source ${isProcedureSideConfigured(unit.source) ? "is-ready" : ""}`}><DatabaseZap size={12} aria-hidden="true" /><code>{commandPreview(unit.source) || "—"}</code></span></td>
                <td>{schemaNameOf(unit.source) || <span className="procedure-muted">—</span>}</td>
                <td>{technologyOf(unit.target) || <span className="procedure-muted">—</span>}</td>
                <td>{unit.target ? ((unit.target.transactionMode ?? "AUTOCOMMIT") === "TRANSACTION" ? t("managedTransaction") : t("autocommit")) : <span className="procedure-muted">—</span>}</td>
                <td>{unit.target && (unit.target.transactionMode ?? "AUTOCOMMIT") === "TRANSACTION" ? (unit.target.commitMode === "NO_COMMIT" ? t("noCommit") : t("commit")) : <span className="procedure-muted">—</span>}</td>
                <td>{(() => { const counter = normalizeProcedureLogCounter((unit.target ?? unit.source)?.logCounter); return <span className={`procedure-counter-chip procedure-counter-chip--${counter.toLowerCase()}`}>{t(LOG_COUNTER_LABEL_KEYS[counter])}</span> })()}</td>
                <td><div className="procedure-task-actions">
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveUp")}: ${task.name || task.id}`}
                    data-tone="info"
                    disabled={index === 0}
                    onClick={() => move(unit.firstIndex, -1)}
                  >
                    <ArrowUp size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveDown")}: ${task.name || task.id}`}
                    data-tone="info"
                    disabled={index === units.length - 1}
                    onClick={() => move(unit.firstIndex, 1)}
                  >
                    <ArrowDown size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("duplicate")}: ${task.name || task.id}`}
                    data-tone="neutral"
                    onClick={() => duplicate(unit.firstIndex)}
                  >
                    <Copy size={15} />
                  </AntActionButton>
                  <AntActionButton tone="ghost"
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("remove")}: ${task.name || task.id}`}
                    data-tone="danger"
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
      </section>
      </Splitter.Panel>
      <Splitter.Panel min={220}>
        {selectedUnit ? (
          <section
            className="procedure-task-editor"
            aria-label={`${t("stepEditor")}: ${(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}`}
          >
            <header>
              <div className="procedure-heading-identity"><span className="procedure-heading-icon procedure-heading-icon--detail" aria-hidden="true"><SquarePen size={17} /></span><div><strong>{t("stepDetails")}</strong><b>{(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}</b></div></div>
              <span className="procedure-step-position"><span className="procedure-step-badge">{units.indexOf(selectedUnit) + 1}</span> / {units.length}</span>
            </header>
            <div className="procedure-detail-body procedure-detail-body--stacked"><div className="procedure-command-tabs procedure-command-tabs--horizontal" role="tablist" aria-label={t("commands")}>
              <AntActionButton tone="ghost" type="button" role="tab" className="procedure-tab procedure-tab--general" aria-selected={selectedDetail === "GENERAL"} onClick={() => setSelectedDetail("GENERAL")}><SlidersHorizontal size={15} aria-hidden="true" />{t("general")}</AntActionButton>
              <AntActionButton tone="ghost" type="button" role="tab" className="procedure-tab procedure-tab--target" aria-selected={selectedDetail === "TARGET"} onClick={() => { setSelectedRole("TARGET"); setSelectedDetail("TARGET"); }}><Database size={15} aria-hidden="true" />{t("targetCommand")}{isProcedureSideConfigured(selectedUnit.target) ? <span className="procedure-tab-dot is-ready" aria-hidden="true" /> : <span className="procedure-tab-dot" aria-hidden="true" />}</AntActionButton>
              <AntActionButton tone="ghost" type="button" role="tab" className="procedure-tab procedure-tab--source" aria-selected={selectedDetail === "SOURCE"} onClick={() => { setSelectedRole("SOURCE"); setSelectedDetail("SOURCE"); }}><DatabaseZap size={15} aria-hidden="true" />{t("sourceCommand")}{isProcedureSideConfigured(selectedUnit.source) ? <span className="procedure-tab-dot is-ready" aria-hidden="true" /> : <span className="procedure-tab-dot" aria-hidden="true" />}</AntActionButton>
            </div>
            <div className="procedure-command-detail">
              {selectedDetail === "GENERAL" ? (() => {
                const primary = selectedUnit.target ?? selectedUnit.source!;
                return <section className="procedure-general-properties">
                  <label className="procedure-general-name"><span>{t("stepName")}</span><AntInput aria-label={t("stepName")} value={primary.name ?? ""} onChange={(event) => {
                    const ids = new Set(selectedUnit.tasks.map((task) => task.id));
                    replaceTasks(value.tasks.map((task) => ids.has(task.id) ? { ...task, name: event.target.value } : task));
                  }} /></label>
                  <div className="procedure-flag-group" role="group" aria-label={t("execute")}>
                    <label className="procedure-checkbox"><AntCheckbox checked={primary.onError === "CONTINUE"} disabled={Boolean(primary.input || primary.output)} onChange={(event) => updateTask(primary, { onError: event.target.checked ? "CONTINUE" : "STOP" })} /><span>{t("ignoreErrors")}</span></label>
                    <label className="procedure-checkbox"><AntCheckbox checked={unitEnabled(selectedUnit)} onChange={(event) => setUnitFlag(selectedUnit, { enabled: event.target.checked })} /><span>{t("execute")}</span></label>
                  </div>
                  <label><span>{t("logCounter")}</span><FormSelect aria-label={t("logCounter")} value={normalizeProcedureLogCounter(primary.logCounter)} onChange={(event) => updateTask(primary, { logCounter: event.target.value as ProcedureTask["logCounter"] })}>{PROCEDURE_LOG_COUNTERS.map((counter) => <option key={counter} value={counter}>{t(LOG_COUNTER_LABEL_KEYS[counter])}</option>)}</FormSelect></label>
                  {primary.input || primary.output ? <p>{t("rowTransferStopsOnError")}</p> : null}
                </section>;
              })() : renderTaskSide(selectedRole, selectedRole === "SOURCE" ? selectedUnit.source : selectedUnit.target)}
            </div></div>
          </section>
        ) : (
          <p className="definition-state">{t("noProcedureSteps")}</p>
        )}
      </Splitter.Panel>
      </Splitter>
    </div>
  );
}
