// SQL and owner-scoped repositories are implemented in P2/P3.
// Every caller must bind the verified user_id, never concatenate user input.
export abstract class D1Repository {
  protected constructor(protected readonly db: D1Database) {}
}
