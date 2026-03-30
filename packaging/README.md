# Packaging

## systemd Services

The repository includes systemd templates under [systemd/](/home/jrml/devel/agiladmin/packaging/systemd):

- [agiladmin@.service.in](/home/jrml/devel/agiladmin/packaging/systemd/agiladmin@.service.in): runs Agiladmin as a systemd template unit
- [pocketbase.service.in](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.service.in): runs PocketBase as a separate service
- [pocketbase.env.example](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.env.example): optional environment overrides for the PocketBase unit
- [GNUmakefile](/home/jrml/devel/agiladmin/packaging/systemd/GNUmakefile): renders the unit templates to their target systemd directory

Expected runtime configuration files:

- Agiladmin app config per instance: `@APP_HOME@/etc/<instance>/agiladmin.yaml`
- optional PocketBase unit env file: `@APP_HOME@/etc/pocketbase.env`

Programs started by the units:

- Agiladmin service runs:

```sh
/usr/bin/java $JAVA_OPTS -jar @APP_HOME@/lib/agiladmin.jar
```

- PocketBase service runs:

```sh
$POCKETBASE_BIN serve --http $POCKETBASE_HTTP --dir $POCKETBASE_DIR --migrationsDir $POCKETBASE_MIGRATIONS_DIR
```

The PocketBase unit defaults to listening on:

```text
127.0.0.1:8090
```

Typical install flow after rendering the templates for your target paths:

```sh
make -C packaging/systemd render DESTDIR="$PWD/stage"
sudo install -m 0644 "$PWD/stage/etc/systemd/system/<app-name>@.service" /etc/systemd/system/<app-name>@.service
sudo install -m 0644 "$PWD/stage/etc/systemd/system/<app-name>-pocketbase.service" /etc/systemd/system/<app-name>-pocketbase.service
sudo install -m 0644 packaging/systemd/pocketbase.env.example <app-home>/etc/pocketbase.env
sudo systemctl daemon-reload
sudo systemctl enable --now <app-name>-pocketbase.service
sudo systemctl enable --now <app-name>@main.service
```

If you use the separate PocketBase service, keep `agiladmin.pocketbase.manage-process: false` in `agiladmin.yaml` so Agiladmin does not try to start PocketBase itself.

The Agiladmin unit is a systemd template unit. `systemctl start <app-name>@<instance>.service` sets `%i` to `<instance>`, and the unit resolves:

- `AGILADMIN_CONF` to `@APP_HOME@/etc/%i/agiladmin.yaml`
- `AGILADMIN_INSTANCE` to `%i`
- runtime and state directories to `<app-name>-%i`
