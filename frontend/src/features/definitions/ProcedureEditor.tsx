import {
  ArrowDown,
  ArrowUp,
  Copy,
  Plus,
  Trash2,
  Undo2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { SqlEditor } from "../../core/ui";
import {
  topologyApi,
  type Environment,
  type LogicalSchema,
} from "../topology/api";
import { useDefinitionsI18n } from "./i18n";
import type {
  ProcedureConnectionRole,
  ProcedureContent,
  ProcedureTask,
} from "./types";

interface Props {
  projectUuid: string;
  value: ProcedureContent;
  onChange: (value: ProcedureContent) => void;
  limits?: {
    maximumTasks: number;
    maximumRowsetRows: number;
    maximumTimeoutSeconds: number;
  };
}
const PAGE_SIZE = 100;

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
      next?.connectionRole === "TARGET" &&
      (!next.input || next.input.fromTask === task.id)
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
  const sql = command.trimStart().toLocaleUpperCase("en-US");
  if (task.connectionRole === "SOURCE") {
    return { type: "SQL", riskClass: "READ_ONLY", requiresApproval: undefined };
  }
  if (/^TRUNCATE\s+TABLE\b/.test(sql)) {
    return { type: "SQL", riskClass: "DESTRUCTIVE", requiresApproval: true };
  }
  if (/^(BEGIN|DECLARE)\b/.test(sql) && sql.includes("DBMS_STATS.GATHER_TABLE_STATS")) {
    return { type: "PLSQL", riskClass: "DESTRUCTIVE", requiresApproval: true };
  }
  if (/^(CREATE|ALTER|DROP|GRANT|REVOKE)\b/.test(sql)) {
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
  const [page, setPage] = useState(0);
  const [undoTasks, setUndoTasks] = useState<ProcedureTask[] | null>(null);
  const [pendingDelete, setPendingDelete] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const [logicalSchemas, setLogicalSchemas] = useState<LogicalSchema[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
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
    const configured = isProcedureSideConfigured(task);
    return (
      <section className={`procedure-side procedure-side--${role.toLowerCase()}`}>
        <div className="procedure-side-context">
            <label>
              <span>{t("logicalSchema")}</span>
              <select value={task?.logicalSchemaUuid ?? ""} onChange={(event) => updateSide(role, task, { logicalSchemaUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {logicalSchemas.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </select>
            </label>
            <label>
              <span>{t("environment")}</span>
              <select value={task?.environmentUuid ?? ""} onChange={(event) => updateSide(role, task, { environmentUuid: event.target.value })}>
                <option value="">{t("notSelected")}</option>
                {environments.map((item) => <option key={item.uuid} value={item.uuid}>{item.name}</option>)}
              </select>
            </label>
            <span className={`procedure-side-state ${configured ? "is-configured" : ""}`}>{t(configured ? "configured" : "notConfigured")}</span>
          </div>
          <label className="procedure-command">
            <span>{t(role === "SOURCE" ? "sourceSql" : "targetSql")}</span>
            <SqlEditor label={t(role === "SOURCE" ? "sourceSql" : "targetSql")} value={task?.command ?? ""} onChange={(command) => {
              const base = task ?? nextTask(role, value.tasks);
              updateSide(role, task, { command, ...inferProcedureTaskMetadata(base, command) });
            }} />
          </label>
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
          <button
            className="definition-button definition-button--quiet"
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
          </button>
          <button
            className="definition-button definition-button--quiet"
            type="button"
            onClick={add}
          >
            <Plus size={15} />
            {t("addStep")}
          </button>
        </div>
      </header>
      {message ? (
        <div className="procedure-reorder-error" role="alert">
          {message}
          {pendingDelete != null ? (
            <span>
              <button type="button" onClick={() => remove(pendingDelete)}>
                {t("confirmRemove")}
              </button>
              <button
                type="button"
                onClick={() => {
                  setPendingDelete(null);
                  setMessage("");
                }}
              >
                {t("cancel")}
              </button>
            </span>
          ) : null}
        </div>
      ) : null}
      <div className="procedure-workbench">
        <aside className="procedure-list-column">
          <div
            className="procedure-task-list"
            role="list"
            aria-label={t("procedureSteps")}
          >
            {visible.map((unit, pageIndex) => {
              const index = safePage * PAGE_SIZE + pageIndex;
              const task = unit.target ?? unit.source!;
              const isSelected = unit.tasks.some((candidate) => candidate.id === selectedTaskId);
              return (
              <article
                className={`procedure-task ${isSelected ? "is-selected" : ""}`}
                key={task.id}
              >
                <button
                  className="procedure-task-select"
                  type="button"
                  onClick={() => {
                    setSelectedTaskId(task.id);
                    setSelectedRole(unit.target ? "TARGET" : "SOURCE");
                  }}
                  aria-pressed={isSelected}
                >
                  <span className="procedure-step-number">{index + 1}</span>
                  <div>
                    <strong>{task.name || task.id}</strong>
                    <small className="procedure-step-route"><span className={isProcedureSideConfigured(unit.source) ? "is-ready" : ""}>{t("source")}</span><span aria-hidden="true">→</span><span className={isProcedureSideConfigured(unit.target) ? "is-ready" : ""}>{t("target")}</span></small>
                  </div>
                </button>
                <div className="procedure-task-actions">
                  <button
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveUp")}: ${task.name || task.id}`}
                    disabled={index === 0}
                    onClick={() => move(unit.firstIndex, -1)}
                  >
                    <ArrowUp size={15} />
                  </button>
                  <button
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("moveDown")}: ${task.name || task.id}`}
                    disabled={index === units.length - 1}
                    onClick={() => move(unit.firstIndex, 1)}
                  >
                    <ArrowDown size={15} />
                  </button>
                  <button
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("duplicate")}: ${task.name || task.id}`}
                    onClick={() => duplicate(unit.firstIndex)}
                  >
                    <Copy size={15} />
                  </button>
                  <button
                    className="definition-icon-button"
                    type="button"
                    aria-label={`${t("remove")}: ${task.name || task.id}`}
                    onClick={() => remove(unit.firstIndex)}
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </article>
              );
            })}
          </div>
          {pages > 1 ? (
            <nav className="procedure-pagination" aria-label={t("stepPages")}>
              <button
                type="button"
                disabled={safePage === 0}
                onClick={() => setPage((current) => current - 1)}
              >
                {t("previous")}
              </button>
              <span>{t("page", { page: safePage + 1, pages })}</span>
              <button
                type="button"
                disabled={safePage >= pages - 1}
                onClick={() => setPage((current) => current + 1)}
              >
                {t("next")}
              </button>
            </nav>
          ) : null}
        </aside>
        {selectedUnit ? (
          <section
            className="procedure-task-editor"
            aria-label={`${t("stepEditor")}: ${(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}`}
          >
            <header>
              <div>
                <p className="eyebrow">{t("selectedStep")}</p>
                <input
                  className="procedure-step-name"
                  aria-label={t("stepName")}
                  value={(selectedUnit.target ?? selectedUnit.source)?.name ?? ""}
                  onChange={(event) => {
                    const ids = new Set(selectedUnit.tasks.map((task) => task.id));
                    replaceTasks(value.tasks.map((task) => ids.has(task.id) ? { ...task, name: event.target.value } : task));
                  }}
                />
              </div>
              <span>
                {units.indexOf(selectedUnit) + 1} / {units.length}
              </span>
            </header>
            <div className="procedure-command-tabs" role="tablist" aria-label={t("commands")}>
              <button type="button" role="tab" aria-selected={selectedRole === "SOURCE"} onClick={() => setSelectedRole("SOURCE")}>{t("sourceCommand")}<span className={isProcedureSideConfigured(selectedUnit.source) ? "is-configured" : ""}>{t(isProcedureSideConfigured(selectedUnit.source) ? "configured" : "notConfigured")}</span></button>
              <button type="button" role="tab" aria-selected={selectedRole === "TARGET"} onClick={() => setSelectedRole("TARGET")}>{t("targetCommand")}<span className={isProcedureSideConfigured(selectedUnit.target) ? "is-configured" : ""}>{t(isProcedureSideConfigured(selectedUnit.target) ? "configured" : "notConfigured")}</span></button>
            </div>
            <div className="procedure-command-detail">
              {renderTaskSide(selectedRole, selectedRole === "SOURCE" ? selectedUnit.source : selectedUnit.target)}
            </div>
          </section>
        ) : (
          <p className="definition-state">{t("noProcedureSteps")}</p>
        )}
      </div>
    </div>
  );
}
