# EPMS Business Requirements

## 1. Purpose and Scope

The Enterprise Project Management System (EPMS) is an organization-based project management application designed to provide a centralized system for organizing teams, projects, tasks, and project-related work.

Each EPMS instance represents a single organization. The system enables the organization to manage its teams and users, coordinate projects across teams, organize project work into tasks, and use project-specific labels to categorize tasks.

EPMS is intended to support the lifecycle of organizational projects from planning through active work, review, delivery, and completion.

### Scope

EPMS will provide functionality to:

- Manage the organization's teams and users.
- Allow users to participate in teams.
- Allow teams to participate in projects.
- Allow projects to involve multiple teams.
- Organize project work into tasks.
- Assign responsibility for task work.
- Create project-specific labels and assign them to tasks.
- Track projects and tasks through defined lifecycles.
- Support planning, active work, review, delivery, returned work, holds, completion, and cancellation.
- Provide comments and discussion for task-related collaboration.
- Support file attachments.
- Provide email and in-application notifications.
- Maintain project and task activity history.
- Maintain an audit history of significant actions.
- Track time and hours worked.
- Provide calendar-based views of scheduled work.
- Provide dashboards and reports.
- Provide search and filtering capabilities.
- Export business information in formats such as PDF and CSV.

## 2. Organization

The organization is the primary business represented within EPMS. Each EPMS instance is dedicated to a single organization.

The organization uses EPMS to manage its teams and users and to coordinate project work across the organization.

Teams, users, projects, tasks, and labels managed within EPMS operate within the context of that organization.

Organization information is treated as system configuration rather than ordinary day-to-day business data.

## 3. Users

Users represent the people who use EPMS to participate in the organization's project work.

Each user belongs to the organization represented by the EPMS instance. Users may participate in teams based on their responsibilities within the organization and may participate in more than one team.

Through their team and project participation, users can contribute to the organization's project work and work with tasks associated with those projects.

Only active users may participate in new work or receive new task assignments.

EPMS will use roles and permissions to control actions that require additional authority. These permissions will support responsibilities such as project management, team leadership, project and task review, and other controlled business operations.

## 4. Teams

Teams represent groups of users who work together within the organization.

The organization can create teams to reflect how its people and project work are organized. Users may participate in multiple teams, and teams may include multiple users.

A team may be created before members are assigned, but it is not available for active project participation until it has at least one active team member.

Teams can participate in projects based on the work they are responsible for performing. A team may participate in multiple projects, and a project may involve multiple teams.

Teams provide a way to organize users and connect them with the projects being carried out by the organization.

A team must continue to have at least one active member in order to remain eligible for active project work.

## 5. Projects

Projects represent organized bodies of work carried out by the organization.

A project may be entered into EPMS while its setup is still incomplete. This allows project information to be prepared before the project is ready to become active within the normal project lifecycle.

Before a project can be finalized, it must have a valid start date, a valid due date, and at least one participating active team. Each participating team must have at least one active team member.

Once those requirements are satisfied and the project is finalized, the project enters the Planned stage of its lifecycle.

A project may involve multiple teams working together toward a common objective. Teams may participate in multiple projects based on their responsibilities within the organization.

Projects provide the structure for organizing tasks and labels and for tracking work through the project lifecycle.

### Project Scheduling

EPMS will maintain a start date and due date for every finalized project.

The due date cannot occur before the start date.

Task schedules must remain within the schedule established for their project. A task cannot begin before its project begins or be due after its project is due.

When additional time is needed for a task beyond the current project due date, the project must first be extended before the task can be extended.

EPMS will prevent changes to project dates that would leave existing task schedules outside the revised project schedule. Conflicting task schedules must be resolved before the project schedule can be shortened or moved.

When a project is completed, EPMS records its completion date automatically.

### Project Lifecycle

EPMS will track projects from initial planning through active work, review, delivery, and completion. Projects may also be placed on hold, returned for additional work after delivery, or cancelled when they will not be completed.

Project lifecycle stages cannot be skipped during the normal workflow.

The normal project lifecycle is:

**Planned → In Progress → In Review → Delivered → Completed**

A delivered project that requires additional work may be Returned. Returned work can move back to the appropriate working stage before proceeding through review and delivery again.

A project may be placed On Hold during its lifecycle. When work resumes, the project returns to the stage it held immediately before being placed On Hold.

A project may be Cancelled before completion.

Completed projects are considered finished and cannot subsequently be placed On Hold or Cancelled.

### Project Statuses

- **Planned** — The project has been finalized and prepared for work, but active work has not started.
- **In Progress** — Work on the project has started and is actively being performed.
- **In Review** — The project team's work is substantially complete and is being reviewed internally before delivery.
- **Delivered** — The project has been delivered to the requesting party or stakeholder and is awaiting acceptance or feedback.
- **Returned** — The delivered project was not accepted and has been returned for additional work or corrections.
- **On Hold** — Work on the project has been temporarily suspended, but the project has not been cancelled.
- **Completed** — The delivered project has been accepted and the project is finished.
- **Cancelled** — The project was stopped before completion and is not expected to resume.

## 6. Tasks

Tasks represent individual units of work that contribute to the completion of a project.

Tasks are created within a project and provide a way to organize the work required to move the project toward completion. A project may contain multiple tasks, while each task belongs to a single project.

A task may initially be unassigned. When a task is assigned, one active user is responsible for completing the work. The assigned user must belong to a team participating in the task's project.

Tasks may use project-specific labels to help categorize and organize the work.

EPMS will allow the organization to track task work as part of the overall progress of a project.

### Task Scheduling

Every task must have a start date and due date.

The due date cannot occur before the task's start date.

Task dates must remain within the dates established for the project. A task cannot begin before its project begins or be due after its project is due.

If a task requires a due date beyond the project's current due date, the project must first be extended.

When a task is completed, EPMS records its completion date automatically.

### Task Priorities

EPMS will support the following task priorities:

- **Low** — Lower urgency and generally addressed after more important work.
- **Medium** — Normal priority for regular project work and the default priority for a task.
- **High** — Important work that should receive attention ahead of normal-priority work.
- **Critical** — Work requiring immediate or near-immediate attention because of its importance or impact.

### Task Lifecycle

The normal task lifecycle is:

**To Do → In Progress → In Review → Completed**

Task lifecycle stages cannot be skipped during the normal workflow.

Work that does not pass review may be Returned for additional work and then progress through review again.

A task may be placed On Hold while work is temporarily suspended. When work resumes, the task returns to the stage it held immediately before being placed On Hold.

A task may be Cancelled before completion.

Completed tasks are considered finished and cannot subsequently be placed On Hold or Cancelled.

### Task Statuses

- **To Do** — The task has been created but work has not started.
- **In Progress** — Work on the task has started and is actively being performed.
- **In Review** — Work on the task is complete and is being reviewed before being considered finished.
- **Returned** — The task did not pass review and has been returned for additional work or corrections.
- **On Hold** — Work on the task has been temporarily suspended.
- **Completed** — The task has been successfully finished and no further work is required.
- **Cancelled** — The task will not be completed and no further work is expected.

## 7. Labels

Labels provide a way to categorize and organize tasks within a project.

Labels are defined for a specific project so they can reflect the needs and terminology of that project. A project may define multiple labels, and those labels can be reused across tasks within the same project.

A task may have multiple labels, allowing its work to be categorized in more than one way. Labels cannot be assigned to tasks belonging to a different project.

Labels can be used for categories such as the type of work, area of responsibility, or other project-specific classifications.

## 8. Business Workflows

### Project Creation and Finalization

EPMS will allow a project to be entered and saved before all information required for active project work is available.

While setup is incomplete, the project remains pending and does not enter the normal project lifecycle.

A project cannot be finalized until it has a valid start date, a valid due date, and at least one participating active team. Each participating team must contain at least one active team member.

A project does not have to be assigned to a team when it is initially created.

Once the project is finalized, it enters the Planned stage.

### Task Creation and Assignment

Tasks are created within a project and may initially remain unassigned.

When responsibility is assigned, the task is assigned to one active user who belongs to a team participating in the project.

Task assignments must remain consistent with team and project participation.

### Task Review

When work on a task is ready for review, the task moves to In Review.

A user with the appropriate Reviewer permission determines whether the work is accepted or requires additional work.

Accepted work may move to Completed. Work requiring correction moves to Returned and must progress through the working and review process again.

Detailed reviewer roles and authorization rules will be defined as part of the EPMS roles and permissions model.

### Project Review and Delivery

Projects move through review and delivery according to the defined project lifecycle.

Permissions determine which users may perform controlled actions such as approving review, delivering a project, returning delivered work, completing a project, or cancelling a project.

The roles and permissions model will include organizational responsibilities such as project management, team leadership, review responsibilities, and project or team membership.

### Returned Work

Returned work identifies work that requires additional attention after review or delivery.

A Returned task goes back into active work before being reviewed again.

A Returned project may return to the appropriate working stage based on the work that remains. It must proceed through delivery again before it can be completed.

### On Hold

Projects and tasks may be placed On Hold when work must be temporarily suspended.

Placing a project or task On Hold requires a standardized reason and an explanatory comment so affected users can understand why work has stopped.

When work resumes, the project or task returns to the lifecycle stage it held immediately before being placed On Hold.

### Completion

Completion represents successful completion of the applicable project or task lifecycle.

EPMS records the completion date automatically when a project or task reaches Completed.

Completed work remains part of the organization's historical records and reporting.

Completed projects and tasks are treated as finished and do not return to active lifecycle stages.

### Cancellation

Projects and tasks may be cancelled before they are completed.

Cancellation requires both a standardized cancellation reason and an explanatory comment.

Cancelled work remains available as historical information and is not permanently deleted.

### User Deactivation

When a user is deactivated, access to EPMS is removed immediately.

If the user is responsible for active tasks, EPMS creates a red action-required alert indicating that the tasks must be reassigned.

Leadership and the people responsible for managing the affected projects receive the alert.

The deactivation remains effective while reassignment work is being completed. The alert remains visible until all affected task assignments have been resolved.

The user's historical participation and completed work remain available.

### Team Removal from a Project

When a team is removed from a project, affected users are notified so they know to stop work associated with that team's participation.

Appropriate leadership is also notified so they are aware of the change and its effect on workload.

EPMS displays a yellow warning when work, task assignments, or other consequences of the team removal still require attention.

Team removal requires a standardized reason and an explanatory comment.

A team cannot be removed if doing so would leave an active project without an eligible participating team. Another eligible team must first be assigned, or the project must be otherwise appropriately resolved.

### Notifications and Alerts

EPMS will provide in-application alerts and notifications for business events requiring awareness or action.

Red alerts identify conditions requiring action before the related business process is considered fully resolved.

Yellow alerts identify warnings or conditions that require awareness and follow-up.

EPMS will also support email notifications for appropriate business events.

Notification recipients will be determined by the affected users, leadership responsibilities, project responsibilities, and applicable permissions.

## 9. Business Constraints

EPMS will enforce business constraints that protect the consistency and historical integrity of organizational project information.

Business records will not be permanently deleted through normal EPMS operations. Records that are no longer active will be deactivated or archived as appropriate.

Deactivated and archived records will be hidden from normal day-to-day activity by default while remaining available for historical reporting. Reports will provide the ability to include or hide inactive and archived information.

Only active users may participate in new work or receive new task assignments. Teams must have at least one active member before they are eligible to participate in projects.

An active project must maintain at least one eligible participating team.

Names must be unique within their appropriate business scope. Team and project names must be unique within the organization, while label names must be unique within their project.

EPMS will also identify potentially confusing similar, look-alike, or sound-alike names. Such names require an authorized override before they can be created or used as a replacement name.

Project and task schedules must remain consistent. Tasks cannot be scheduled outside the dates established for their project, and project schedule changes cannot leave existing tasks outside the revised project schedule.

Significant cancellations, removals, deactivations, and holds must retain the reason and explanatory information needed to understand why the action occurred.

Historical activity and audit information will preserve relevant business context as it existed when the event occurred. Later changes to current information will not rewrite the historical meaning of previously recorded activity.

Role-based permissions will control business actions that require additional authority, including review, delivery, return, completion, cancellation, leadership functions, and authorized overrides.

## 10. Future and Out-of-Scope Requirements

### Initial EPMS Scope

The initial EPMS application will include the following capabilities:

- Task comments and discussions.
- File attachments.
- Email notifications.
- In-application notifications and alerts.
- Project and task activity history.
- Audit logging.
- Time tracking and hours worked.
- Calendar views.
- Dashboards and reporting.
- Search and filtering.
- Data export, including formats such as PDF and CSV.

Detailed requirements for these capabilities will be defined as their corresponding application features are designed and implemented.

### Future Requirements

The following capabilities are not required for the initial EPMS implementation but may be considered for future development:

- Project budgeting and cost management.
- A dedicated mobile application.
- Third-party service integrations.

The initial architecture should avoid unnecessary design decisions that would prevent these capabilities from being added in the future.

### Out of Scope

External or client user accounts are outside the scope of the initial EPMS application.

The initial EPMS application is intended for users who belong to the organization represented by the EPMS instance. External stakeholders or clients will not receive direct EPMS accounts as part of the initial implementation.
