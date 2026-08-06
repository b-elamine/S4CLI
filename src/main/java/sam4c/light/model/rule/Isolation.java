package sam4c.light.model.rule;

import sam4c.light.model.ref.Ref;

// sctx/tctx must share no path. By default "path" is single-hop (one shared connector).
// An optional third argument, via, names an approved-mediator context: when given, the
// check switches to full multi-hop reachability and only flags a path that reaches tctx
// from sctx WITHOUT passing through a via component -- a path that goes through the
// mediator is the intended, legitimate one and is not a violation.
public record Isolation(Ref sctx, Ref tctx, Ref via) implements SecurityRule {}
