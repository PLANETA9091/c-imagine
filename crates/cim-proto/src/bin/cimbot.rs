//! cimbot — the M4 protocol bot CLI (integration + local harness driver).
//!
//! Usage:
//!   cimbot --host 127.0.0.1 --port 25565 --name cimbot [--timeout 120]
//!          [--report bot-report.json] [--join-only] [--join-grace 12]
//!          [--expect-entity minecraft:oak_boat]
//!          [--expect-block x,y,z | --no-expect-block]
//!          [--expect-postfx minecraft:creeper | --no-expect-postfx]
//!
//! Exit 0 = joined (+ all expectation legs observed unless join-only).

use cim_proto::bot::{self, Expectations};

fn main() {
    let mut host = String::from("127.0.0.1");
    let mut port: u16 = 25565;
    let mut name = String::from("cimbot");
    let mut timeout: u64 = 120;
    let mut report = String::new();
    let mut exp = Expectations::default();
    let mut join_only = false;

    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        let val = |i: &mut usize| -> String {
            *i += 1;
            args.get(*i).cloned().unwrap_or_default()
        };
        match args[i].as_str() {
            "--host" => host = val(&mut i),
            "--port" => port = val(&mut i).parse().unwrap_or(port),
            "--name" => name = val(&mut i),
            "--timeout" => timeout = val(&mut i).parse().unwrap_or(timeout),
            "--report" => report = val(&mut i),
            "--expect-entity" => exp.entity_type = val(&mut i),
            "--expect-extra-entity" => exp.entity_type_extra = Some(val(&mut i)),
            "--expect-block" => {
                let v = val(&mut i);
                let p: Vec<&str> = v.split(',').collect();
                if p.len() == 3 {
                    exp.block_at = Some((
                        p[0].trim().parse().unwrap_or(0),
                        p[1].trim().parse().unwrap_or(0),
                        p[2].trim().parse().unwrap_or(0),
                    ));
                }
            }
            "--no-expect-block" => exp.block_at = None,
            "--expect-postfx" => exp.post_fx_effect = Some(val(&mut i)),
            "--no-expect-postfx" => exp.post_fx_effect = None,
            "--join-only" => join_only = true,
            "--join-grace" => {
                let s: u64 = val(&mut i).parse().unwrap_or(10);
                exp.join_grace = std::time::Duration::from_secs(s);
            }
            other => {
                eprintln!("unknown arg: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }

    exp.join_only = join_only;
    eprintln!(
        "[cimbot] joining {}:{} as {} (protocol 777, timeout {}s, join_only={join_only})",
        host, port, name, timeout
    );
    let rep = bot::run(
        &host,
        port,
        &name,
        std::time::Duration::from_secs(timeout),
        exp,
        None,
    );

    if !report.is_empty() {
        let json = serde_json::to_string_pretty(&rep).expect("report json");
        std::fs::write(&report, json).expect("write report");
        eprintln!("[cimbot] report written to {report}");
    }

    eprintln!("[cimbot] result: ok={} checks:", rep.ok);
    for c in &rep.checks {
        eprintln!(
            "[cimbot]   [{}] {} {}",
            if c.passed { "PASS" } else { "FAIL" },
            c.name,
            c.detail
        );
    }
    for e in &rep.errors {
        eprintln!("[cimbot]   error: {e}");
    }

    std::process::exit(if rep.ok { 0 } else { 1 });
}
