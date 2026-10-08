# EPMS Domain Rules

## EPMS Instance and Organization

- An EPMS instance represents exactly one organization.
- An organization can have only one EPMS instance.
- Organization configuration is managed as system configuration rather than normal day-to-day business activity.

## Organization and Teams

- An organization has one or more teams.
- A team belongs to exactly one organization.
- A team must have at least one active team member before it can become active and participate in projects.
- A team may be created without members, but it remains in a pending setup state until at least one active team member is assigned.
- A team with no active members cannot participate in a project.

## Organization and Users

- An organization has one or more users.
- A user belongs to exactly one organization.
- Only active users may participate in new work.

## Teams and Users

- An active team has one or more active team members.
- A user may belong to zero or more teams.
- A user may belong to multiple teams.

## Teams and Projects

- A team can participate in zero or more projects.
- A project must involve one or more active teams before it can be finalized.
- A project can involve multiple teams.
- A participating team must have at least one active team member.
- An active project cannot be left without a participating active team.
- Removing the last participating team from an active project is not permitted until another eligible team is assigned or the project is otherwise appropriately resolved.

## Project Setup and Finalization

- A project may be created and saved while its setup is incomplete.
- An incomplete project remains in a pending setup state and does not participate in the normal project lifecycle.
- A pending project does not require dates or an assigned team while it remains in setup.
- Before a project can be finalized, it must have a start date.
- Before a project can be finalized, it must have a due date.
- Before a project can be finalized, it must have at least one participating active team.
- Every participating team must have at least one active team member.
- A project's due date cannot be earlier than its start date.
- A finalized project enters the normal project lifecycle in the Planned status.

## Projects and Tasks

- A project may have zero or more tasks.
- A task belongs to exactly one project.

## Tasks and Users

- A task may be unassigned or assigned to one user.
- An assigned user is responsible for completing the task.
- A task may only be assigned to an active user.
- A task may only be assigned to a user who belongs to a team participating in the task's project.

## Projects and Labels

- A project may define zero or more labels.
- A label belongs to exactly one project.

## Tasks and Labels

- A task may have zero or more labels.
- A label may be assigned to zero or more tasks within its project.
- A task cannot be assigned a label belonging to a different project.

## Project Statuses

- **Planned** — The project has been finalized and prepared for work, but active work has not started.
- **In Progress** — Work on the project has started and is actively being performed.
- **In Review** — The project team's work is substantially complete and is being reviewed internally before delivery.
- **Delivered** — The project has been delivered to the requesting party or stakeholder and is awaiting acceptance or feedback.
- **Returned** — The delivered project was not accepted and has been returned for additional work or corrections.
- **On Hold** — Work on the project has been temporarily suspended, but the project has not been cancelled.
- **Completed** — The delivered project has been accepted and the project is finished.
- **Cancelled** — The project was stopped before completion and is not expected to resume.

## Project Status Transitions

- Project statuses cannot be skipped during the normal project lifecycle.
- The normal project lifecycle is **Planned → In Progress → In Review → Delivered → Completed**.
- A Delivered project may be moved to Returned when additional work or corrections are required.
- A Returned project may return to In Progress or In Review, depending on the work required.
- A Returned project must progress through Delivered again before it can reach Completed.
- A project may be placed On Hold from any non-completed status.
- When an On Hold project resumes, it returns to the status it held immediately before being placed On Hold.
- A project may be Cancelled from any non-completed status.
- Completed is a terminal status.
- A project cannot be placed On Hold or Cancelled after it has been Completed.

## Task Statuses

- **To Do** — The task has been created but work has not started.
- **In Progress** — Work on the task has started and is actively being performed.
- **In Review** — Work on the task is complete and is being reviewed before being considered finished.
- **Returned** — The task did not pass review and has been returned for additional work or corrections.
- **On Hold** — Work on the task has been temporarily suspended.
- **Completed** — The task has been successfully finished and no further work is required.
- **Cancelled** — The task will not be completed and no further work is expected.

## Task Status Transitions

- Task statuses cannot be skipped during the normal task lifecycle.
- The normal task lifecycle is **To Do → In Progress → In Review → Completed**.
- A task that does not pass review may be moved from In Review to Returned.
- A Returned task must return to In Progress for additional work before progressing through In Review again.
- A task may be placed On Hold from any non-completed status.
- When an On Hold task resumes, it returns to the status it held immediately before being placed On Hold.
- A task may be Cancelled from any non-completed status.
- Completed is a terminal status.
- A task cannot be placed On Hold or Cancelled after it has been Completed.
- Moving a task from In Review to Completed or Returned requires the appropriate reviewer permission.

## Task Priorities

- **Low** — The task has lower urgency and can generally be addressed after more important work.
- **Medium** — The task has normal priority and should be handled as part of the project's regular work.
- **Medium** is the default task priority.
- **High** — The task is important and should receive attention ahead of normal-priority work.
- **Critical** — The task requires immediate or near-immediate attention because of its importance or impact.

## Projects and Dates

- A pending project may be saved without a start date or due date.
- Every project must have a start date and due date before it can be finalized.
- A project's due date cannot be earlier than its start date.
- A project's completion date is recorded automatically when the project is Completed.
- A project that has not been Completed does not have a completion date.
- A task's start date cannot be earlier than its project's start date.
- A task's due date cannot be later than its project's due date.
- A project's due date must be extended before a task's due date can be extended beyond the project's current due date.
- A project's start date cannot be moved later if doing so would place an existing task's start date before the project's new start date.
- A project's due date cannot be moved earlier if doing so would place an existing task's due date after the project's new due date.

## Tasks and Dates

- Every task must have a start date and due date.
- A task's due date cannot be earlier than its start date.
- A task's start date cannot be earlier than its project's start date.
- A task's due date cannot be later than its project's due date.
- A task's completion date is recorded automatically when the task is Completed.
- A task that has not been Completed does not have a completion date.

## Reviews and Permissions

- Role-based permissions determine who may perform controlled project and task operations.
- A user must have the appropriate reviewer permission to approve or return a task that is In Review.
- Appropriate permissions determine who may move projects through review, delivery, return, completion, cancellation, and other controlled lifecycle operations.
- Project and team membership roles, including roles such as Project Manager, Team Lead, Reviewer, and Project or Team Member, will be defined as part of the authorization model.

## Cancellation

- Cancelling a project requires a standardized cancellation reason and an explanatory comment.
- Cancelling a task requires a standardized cancellation reason and an explanatory comment.
- Cancelled projects and tasks retain their historical records.
- Cancelled projects and tasks cannot transition to Completed.

## On Hold

- Placing a project On Hold requires a standardized reason and an explanatory comment.
- Placing a task On Hold requires a standardized reason and an explanatory comment.
- The reason and comment remain part of the historical record.
- Resuming an On Hold project or task returns it to the lifecycle status it held immediately before being placed On Hold.

## User Deactivation and Task Reassignment

- Users are not permanently deleted through normal EPMS operations.
- Deactivating a user removes the user's access to EPMS immediately.
- A deactivated user cannot participate in new work or receive new task assignments.
- A deactivated user's historical activity and records are retained.
- If a user is deactivated while responsible for active tasks, EPMS must create a red action-required alert indicating that the affected tasks require reassignment.
- The user's access removal takes effect immediately even when task reassignment is still required.
- The red action-required alert remains until all affected task assignments have been resolved.
- Leadership and the managers responsible for affected projects must receive the action-required alert.
- A deactivated user's historical participation and completed work remain available for reporting.

## Team Removal from Projects

- Removing a team from a project requires a standardized reason and an explanatory comment.
- Team removal takes effect immediately when permitted by the project participation rules.
- Users affected by the team's removal must be notified so they know to stop work associated with that team's participation.
- Appropriate team leadership must be notified of the change and resulting reduction in project workload.
- EPMS must display a yellow warning alert when consequences of the team removal still require attention.
- Tasks and assignments affected by the team's removal must be resolved as part of the removal process.
- Removing a team cannot leave an active project without at least one participating eligible team.

## Team Membership Changes

- Removing or deactivating a user does not erase the user's historical team membership or work history.
- If removal or deactivation affects active task assignments, those assignments must be reassigned.
- If removal of the final active member would leave a participating team without an active member, the resulting team and project participation must be resolved.
- A team without an active member cannot remain eligible for new project work.

## Removal and Deactivation Reasons

- Significant removals and deactivations must record a standardized reason.
- EPMS must provide an explanatory comment field for additional context.
- Standardized reason lists may differ based on the type of record or action.
- When Other is selected as the standardized reason, an explanatory comment is required.

## Record Retention and Historical Data

- Business records are not permanently deleted through normal EPMS operations.
- Records that are no longer active are deactivated or archived as appropriate.
- Deactivated and archived records are hidden from normal day-to-day activity by default.
- Historical records remain available for reporting.
- Reports must provide a way to include or hide deactivated and archived records.
- Historical activity and audit records preserve relevant business information as it existed when the recorded event occurred.
- Later changes to current entity information must not rewrite the historical meaning of previously recorded activity.

## Names and Uniqueness

- Names must be unique within their applicable business scope.
- Team names must be unique within the organization.
- Project names must be unique within the organization.
- Label names must be unique within their project.
- Exact duplicate names are prohibited within the applicable scope.
- EPMS must detect potentially confusing similar, look-alike, or sound-alike names.
- Creating or renaming a record with a potentially confusing name requires an authorized override.

## Notifications and Alerts

- EPMS must support in-application notifications and alerts for business events requiring user awareness or action.
- EPMS must support email notifications for appropriate business events.
- Red alerts represent conditions requiring action before related business cleanup is considered resolved.
- Yellow alerts represent warnings or conditions requiring awareness and follow-up.
- Notification recipients are determined by the affected users, leadership responsibilities, project responsibilities, and applicable permissions.

## Activity History and Auditing

- EPMS must retain activity history for projects and tasks.
- EPMS must maintain an audit history for significant business and administrative actions.
- Historical activity must remain available even when related users, teams, projects, or other records are later deactivated or archived.
