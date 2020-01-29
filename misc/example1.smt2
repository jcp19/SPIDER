(set-option :produce-unsat-cores true)

; PROGRAM ORDER CONSTRAINTS
(declare-const MAX Int)
(assert (= MAX 8))
(declare-const START_Thread-1@10.0.0.1 Int)
(assert (and (>= START_Thread-1@10.0.0.1 0) (<= START_Thread-1@10.0.0.1 MAX)))
(declare-const W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12 Int)
(assert (and (>= W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12 0) (<= W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12 MAX)))
(declare-const END_Thread-1@10.0.0.1 Int)
(assert (and (>= END_Thread-1@10.0.0.1 0) (<= END_Thread-1@10.0.0.1 MAX)))
(assert (! (<  START_Thread-1@10.0.0.1 W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12 END_Thread-1@10.0.0.1 ):named PC2))
(declare-const START_main@10.0.0.1 Int)
(assert (and (>= START_main@10.0.0.1 0) (<= START_main@10.0.0.1 MAX)))
(declare-const CREATE_main@10.0.0.1_Thread-1@10.0.0.1 Int)
(assert (and (>= CREATE_main@10.0.0.1_Thread-1@10.0.0.1 0) (<= CREATE_main@10.0.0.1_Thread-1@10.0.0.1 MAX)))
(declare-const W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7 Int)
(assert (and (>= W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7 0) (<= W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7 MAX)))
(declare-const W_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8 Int)
(assert (and (>= W_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8 0) (<= W_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8 MAX)))
(declare-const END_main@10.0.0.1 Int)
(assert (and (>= END_main@10.0.0.1 0) (<= END_main@10.0.0.1 MAX)))
(assert (! (<  START_main@10.0.0.1 CREATE_main@10.0.0.1_Thread-1@10.0.0.1 W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7 W_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8 END_main@10.0.0.1 ):named PC3))

; FORK-START CONSTRAINTS
(assert (! (< CREATE_main@10.0.0.1_Thread-1@10.0.0.1 START_Thread-1@10.0.0.1):named FS4))

; JOIN-END CONSTRAINTS

; WAIT-NOTIFY CONSTRAINTS

; LOCKING CONSTRAINTS

; SEND-RECEIVE CONSTRAINTS

; RECEIVE-HANDLER LINKAGE CONSTRAINTS
