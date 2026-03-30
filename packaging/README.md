# Packaging

## systemd Services

The repository includes systemd templates under [systemd/](/home/jrml/devel/agiladmin/packaging/systemd):

- [agiladmin@.service.in](/home/jrml/devel/agiladmin/packaging/systemd/agiladmin@.service.in): runs Agiladmin as a systemd template unit
- [pocketbase.service.in](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.service.in): runs PocketBase as a separate service
- [pocketbase.env.example](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.env.example): optional environment overrides for the PocketBase unit
- [GNUmakefile](/home/jrml/devel/agiladmin/packaging/systemd/GNUmakefile): renders the unit templates to their target systemd directory

Default packaging paths:

- application home: `/opt/agiladmin`
- systemd unit directory: `/etc/systemd/system`
- default Agiladmin instance: `main`, overridden by `AGILADMIN_INSTANCE`

Expected runtime configuration files:

- Agiladmin app config per instance: `@APP_HOME@/<instance>/agiladmin.yaml`
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
sudo env AGILADMIN_INSTANCE="${AGILADMIN_INSTANCE:-main}" make -C packaging/systemd enable
```

If you use the separate PocketBase service, keep `agiladmin.pocketbase.manage-process: false` in `agiladmin.yaml` so Agiladmin does not try to start PocketBase itself.

The Agiladmin unit is a systemd template unit. `systemctl start <app-name>@<instance>.service` sets `%i` to `<instance>`, and the unit resolves:

- `WorkingDirectory` to `@APP_HOME@/%i`
- `AGILADMIN_HOME` to `@APP_HOME@/%i`
- `AGILADMIN_CONF` to `@APP_HOME@/%i/agiladmin.yaml`
- `AGILADMIN_INSTANCE` to `%i`
- runtime and state directories to `<app-name>-%i`

That layout lets each instance keep its own config and data tree under `@APP_HOME@/%i/`, for example `etc/` plus any instance-specific budgets or uploaded files.

Agiladmin reads the budgets directory from the instance config file, not from `%i` directly. To keep budgets under the instance tree, set `:agiladmin :budgets :path` in `@APP_HOME@/%i/agiladmin.yaml` to an instance-local path such as `@APP_HOME@/%i/budgets/`.

The top-level `make install` now renders the default instance config from a template, so the installed `agiladmin.yaml` uses the active `APP_HOME` and instance name instead of copying a static sample verbatim.

The PocketBase unit uses `User=@APP_NAME@` and `Group=@APP_NAME@`. Create that service account before enabling the unit, for example:

```sh
sudo groupadd --system <app-name>
sudo useradd --system --gid <app-name> --home-dir <app-home> --shell /usr/sbin/nologin <app-name>
```
