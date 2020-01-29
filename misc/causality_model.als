sig Thread {} -- change name from Thread to location, include different nodes and threads

/*
sig Node {
    threads: some Thread,
    sharedVariables: some SharedVariable
}

fact {
    all disj n1, n2 : Node | no (n1.threads & n2.threads)
    all disj n1, n2 : Node | no (n1.sharedVariables & n2.sharedVariables)
}*/

sig SharedVariable {}
sig Message {
    -- TODO: I believe these constraints can be simplified/removed
    send : one Snd,
    receive : lone Rcv -- a message may not be received
}

-- Notice that you may ommit the SharedVariables and Messages from the trace
-- to focus on the hb-relation (only when you have enough confidence in the model)

-- Types of events
abstract sig Event {
    where : one Thread
}

sig Snd extends Event {
    message : one Message
}
sig Rcv extends Event {
    message : one Message
}
sig Read extends Event {
    variable : one SharedVariable
}
sig Write extends Event {
    variable : one SharedVariable
}
sig Fork extends Event {
    newThread : one Thread
}

sig Join extends Event {
    finishedThread : one Thread
}
sig ThreadInit extends Event {}
sig ThreadEnd extends Event {}

-- HB semantics
sig HB {
    hb : Event -> Event,
    nonTransitiveHB : Event -> Event,
    -- complement + nonTransitiveHB is the intuitive notion of HB relation
    -- complement : Event -> Event
}


fact nonTransitiveHBSemantics {
    all h : HB | no h.nonTransitiveHB & iden
    -- nonTransitiveHB is the transitive reduction of hb
    all h : HB, disj a, b : Event |
      -- the condition before the or ensures that the intra-thread HB relations will still be shown when they
      -- connect to events already connected
      ((a.where = b.where and a->b in h.hb and (no x : Event | x.where = a.where and a->x in h.hb and x->b in h.hb) )
          or (all x : Event | (a -> b in h.hb and (not (a -> x in h.hb and x -> b in h.hb)))))
      iff a -> b in h.nonTransitiveHB
}


-- 1) HB is a strict partial order
fact hb_partial_order {
    all e1, e2 : Event, h : HB | 
        e2 -> e1 in h.hb implies not e1->e2 in h.hb -- asymetry and irreflexivity
    all e1, e2, e3 : Event, h : HB |
        (e1 -> e2 in h.hb and e2 -> e3 in h.hb) implies e1 -> e3 in h.hb -- transitivity
}
-- 2) Thread creation and start semantics
fact thread_start {
    -- there is at most one main thread (?) (this one is very dubious)
    
    -- every thread has a threadinit event
    all t : Thread, e : Event, h : HB | one init : ThreadInit | init.where in t and (e.where in t implies (init -> e in h.hb or e = init))
    all t : Thread | t in ThreadInit.where

    -- fork_t < init_t
    all e1, e2 : Event, h : HB | 
        (e1 in Fork and e2 in ThreadInit and e1.newThread = e2.where)
            implies e1 -> e2 in h.hb
    -- a thread cannot fork to create itself
    all e : Fork | e.newThread != e.where

    -- a join can only exist when there is a corresponding fork
    all join : Join | one fork : Fork | join.finishedThread in fork.newThread
    -- a join must occur on the thread that executed  fork
    all join : Join, fork : Fork |
        fork.newThread = join.finishedThread implies fork.where = join.where
    
    -- a thread is terminated by one join
    all disj e1, e2 : Join | e1.finishedThread != e2.finishedThread
    
    -- fork < join
    all disj fork : Fork, join : Join, h : HB | fork.newThread = join.finishedThread implies
        fork -> join in h.hb

    -- if a thread has a threadend event, it must be the last one
    all h : HB | all end_event: ThreadEnd | no e : Event | end_event -> e in h.hb and end_event.where = e.where
}

-- 3) Thread finish and Join semantics

-- 4) Network semantics
-- a thread does not send a message to itself
fact message_not_reflexive {
    all snd : Snd, rcv : Rcv |
        snd.message = rcv.message implies snd.where != rcv.where
}
-- snd < rcv
fact send_before_rcv {
    all m : Message, h : HB | some m.receive implies m.send -> m.receive in h.hb
}

-- two messages cannot be received or sent in the same event
fact uniq_exchange {
    all disj m1, m2 : Message | m1.send != m2.send and
        ((some m1.receive and some m2.receive) implies m1.receive != m2.receive)
}

-- the 'send' field of a message is the Snd whose 'message' field is the message; same for Rcv
fact {
    all m : Message, s : Snd | s.message = m iff m.send = s
    all m : Message, r : Rcv | r.message = m iff m.receive = r
}

-- 5) Causal order between events in the same thread
fact same_thread_order {
    -- basta dizer : para quaisquer dois eventos numa mesma thread e1 e2, e1 < e2 ou e2 < e1
    all disj e1, e2 : Event, h : HB | e1.where = e2.where implies (e1 -> e2 in h.hb or e2 -> e1 in h.hb)
    -- a thread begins with a ThreadInit event
    --all t : Thread | some e : ThreadInit | e -> t in where
    
}

-- 6) Causal order between events in different threads
-- nonTransitiveHB has some restrictions when relating events from different threads; this ensures that there are no more
-- causally ordered events than it makes sense to exist
fact {
    all h : HB, a, b : Event |
        (a -> b in h.nonTransitiveHB and a.where != b.where) implies (a -> b in Snd -> Rcv + Fork -> ThreadInit + ThreadEnd -> Join) 
}


-- REDUNDANCY
-- define the redundancy criterion

pred show {
    some Thread
    one HB
}

assert transitiveClosureCorrectness {
    all h:HB | h.hb = ^(h.nonTransitiveHB)
}

/*
assert noConcurrentWrites {
    all disj e1, e2 : Write, h : HB | e1 -> e2 in h.hb or e2 -> e1 in h.hb
}

check noConcurrentWrites
*/

-- check transitiveClosureCorrectness for 6
run show for 8 but 3 Thread
