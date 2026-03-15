# Packaging

## systemd Services

The repository includes systemd templates under [systemd/](/home/jrml/devel/agiladmin/packaging/systemd):

- [agiladmin.service.in](/home/jrml/devel/agiladmin/packaging/systemd/agiladmin.service.in): runs the Agiladmin application jar
- [pocketbase.service.in](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.service.in): runs PocketBase as a separate service
- [pocketbase.env.example](/home/jrml/devel/agiladmin/packaging/systemd/pocketbase.env.example): optional environment overrides for the PocketBase unit

Expected runtime configuration files:

- Agiladmin app config: `@APP_HOME@/etc/agiladmin.yaml`
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
sudo install -m 0644 packaging/systemd/<app-name>.service /etc/systemd/system/<app-name>.service
sudo install -m 0644 packaging/systemd/<app-name>-pocketbase.service /etc/systemd/system/<app-name>-pocketbase.service
sudo install -m 0644 packaging/systemd/pocketbase.env.example <app-home>/etc/pocketbase.env
sudo systemctl daemon-reload
sudo systemctl enable --now <app-name>-pocketbase.service
sudo systemctl enable --now <app-name>.service
```

If you use the separate PocketBase service, keep `agiladmin.pocketbase.manage-process: false` in `agiladmin.yaml` so Agiladmin does not try to start PocketBase itself.
