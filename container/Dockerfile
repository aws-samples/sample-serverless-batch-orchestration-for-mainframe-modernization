# =============================================================================
# FAITHFUL REFERENCE Dockerfile (not buildable as-is — requires your licensed
# AWS Blu Age / Gapwalk artifacts). Shows the single-image / command-override
# structure. For a container that actually builds and runs, see
# container/runnable-stub/Dockerfile.
# =============================================================================

# 1) Base = your Blu Age / Gapwalk runtime image (Tomcat + gapwalk-application.war).
#    This is produced from your AWS Transform output and is not publicly available.
FROM your-registry/gapwalk-base:latest AS runtime

ENV CATALINA_HOME=/opt/tomcat
ENV JAVA_OPTS="-Djob.working.dir=/opt/tomcat/workdir"
ENV SPRING_CONFIG_LOCATION=file:/opt/tomcat/workdir/config/
ENV WORKING_DIRECTORY=/opt/tomcat/workdir

# 2) Deploy the transformed application WAR (your COBOL->Java programs) alongside
#    the Gapwalk webapps already present in the base image.
COPY build/your-frs-service.war ${CATALINA_HOME}/webapps/

# 3) Config + job scripts.
COPY app-config/       ${WORKING_DIRECTORY}/config/
COPY jobs/functions.groovy jobs/S3FileHandler.groovy ${WORKING_DIRECTORY}/utils/
COPY jobs/*.jcl.groovy ${WORKING_DIRECTORY}/scripts/

# 4) Program selection: symlink each <JOB>.jcl.groovy to an extensionless name so
#    the ECS Command override (["JOB2A"]) maps directly to a script name.
RUN cd ${WORKING_DIRECTORY}/scripts && \
    for f in *.jcl.groovy; do ln -sf "$f" "${f%.jcl.groovy}"; done

COPY entrypoint.sh /opt/tomcat/bin/entrypoint.sh
RUN chmod +x /opt/tomcat/bin/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/opt/tomcat/bin/entrypoint.sh"]
CMD ["JOB1"]   # default; overridden per state by the Step Functions ECS Command
