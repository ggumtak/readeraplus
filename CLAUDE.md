# ReaderaPlus: working rules

## Which model does what (user, 2026-10-06)

- Implementation and design (new features, refactors, anything that writes code): the main, strongest model.
- Debugging and CI log analysis (reading Actions logs, the screenshots job, crash and perf traces, bug hunts, review
  passes): a lighter model. When delegating to a subagent or workflow agent, set its model option to the lighter one
  for this work; leave it unset for implementation agents.
- The user can still switch the session's model with /model; a request in the conversation wins over this file.
