package com.external.plugins;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionRequest;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.DBAuth;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStructure;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.Endpoint;
import com.appsmith.external.exceptions.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.exceptions.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.models.RequestParamDTO;
import com.appsmith.external.models.SSLDetails;
import com.appsmith.external.plugins.BasePlugin;
import com.appsmith.external.plugins.PluginExecutor;
import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB.Builder;
import com.arangodb.ArangoDBException;
import com.arangodb.ArangoDatabase;
import com.arangodb.Protocol;
import com.arangodb.entity.CollectionEntity;
import com.arangodb.model.CollectionsReadOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.ObjectUtils;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;
import org.pf4j.util.StringUtils;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.appsmith.external.constants.ActionConstants.ACTION_CONFIGURATION_BODY;
import static com.external.utils.SSLUtils.setSSLContext;
import static com.external.utils.SSLUtils.setSSLParam;
import static com.external.utils.StructureUtils.generateTemplatesAndStructureForACollection;
import static com.external.utils.StructureUtils.getOneDocumentQuery;

public class ArangoDBPlugin extends BasePlugin {

    private static long DEFAULT_PORT = 8529L;

    public ArangoDBPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Slf4j
    @Extension
    public static class ArangoDBPluginExecutor implements PluginExecutor<ArangoDatabase> {

        private final Scheduler scheduler = Schedulers.elastic();

        @Override
        public Mono<ActionExecutionResult> execute(ArangoDatabase db,
                                                   DatasourceConfiguration datasourceConfiguration,
                                                   ActionConfiguration actionConfiguration) {

            String query = actionConfiguration.getBody();
            List<RequestParamDTO> requestParams = List.of(new RequestParamDTO(ACTION_CONFIGURATION_BODY,
                    query, null, null, null));
            if (StringUtils.isNullOrEmpty(query)) {
                return Mono.error(
                        new AppsmithPluginException(
                                AppsmithPluginError.PLUGIN_EXECUTE_ARGUMENT_ERROR,
                                "Missing required parameter: Query."
                        )
                );
            }

            return Mono.fromCallable(() -> {
                ArangoCursor<Map> cursor = db.query(query, null, null, Map.class);
                List<Map> docList = new ArrayList<>();
                docList.addAll(cursor.asListRemaining());
                ActionExecutionResult result = new ActionExecutionResult();
                result.setBody(objectMapper.valueToTree(docList));
                result.setIsExecutionSuccess(true);
                System.out.println(Thread.currentThread().getName() + ": In the ArangoDBPlugin, got action execution result");
                return result;
            })
                    .onErrorResume(error -> {
                        ActionExecutionResult result = new ActionExecutionResult();
                        result.setIsExecutionSuccess(false);
                        result.setErrorInfo(error);
                        return Mono.just(result);
                    })
                    // Now set the request in the result to be returned back to the server
                    .flatMap(actionExecutionResult -> {
                        ActionExecutionRequest request = new ActionExecutionRequest();
                        request.setQuery(query);
                        request.setRequestParams(requestParams);
                        actionExecutionResult.setRequest(request);
                        return Mono.just(actionExecutionResult);
                    })
                    .subscribeOn(scheduler);
        }


        @Override
        public Mono<ArangoDatabase> datasourceCreate(DatasourceConfiguration datasourceConfiguration) {

            return (Mono<ArangoDatabase>) Mono.fromCallable(() -> {

                DBAuth auth = (DBAuth) datasourceConfiguration.getAuthentication();
                if (isAuthenticationMissing(auth)) {
                    return Mono.error(
                            new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_DATASOURCE_ARGUMENT_ERROR,
                                    "Could not find required authentication info. At least one of 'Username', " +
                                            "'Password', 'Database Name' fields is missing. Please edit the " +
                                            "'Username', 'Password' and 'Database Name' fields to provide " +
                                            "authentication info."
                            )
                    );
                }

                Builder dbBuilder;

                if (CollectionUtils.isEmpty(datasourceConfiguration.getEndpoints())) {
                    return Mono.error(
                            new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_DATASOURCE_ARGUMENT_ERROR,
                                    "Could not find host address. Please edit the 'Host Address' and/or the 'Port' " +
                                            "field to provide the desired endpoint."
                            )
                    );
                }
                else {
                    List<Endpoint> nonEmptyEndpoints = datasourceConfiguration.getEndpoints().stream()
                            .filter(endpoint -> isNonEmptyEndpoint(endpoint))
                            .collect(Collectors.toList());

                    if (CollectionUtils.isEmpty(nonEmptyEndpoints)) {
                        return Mono.error(
                                new AppsmithPluginException(
                                        AppsmithPluginError.PLUGIN_DATASOURCE_ARGUMENT_ERROR,
                                        "Could not find host address. Please edit the 'Host Address' and/or the 'Port' " +
                                                "field to provide the desired endpoint."
                                )
                        );
                    }

                    dbBuilder = getBasicBuilder(auth);
                    nonEmptyEndpoints.stream()
                            .forEach(endpoint -> {
                                String host = endpoint.getHost();
                                int port = (int) (long) ObjectUtils.defaultIfNull(endpoint.getPort(), DEFAULT_PORT);
                                dbBuilder.host(host, port);
                            });
                }

                /**
                 * - datasource.connection, datasource.connection.ssl, datasource.connection.ssl.authType objects
                 * are never expected to be null because form.json always assigns a default value to authType object.
                 */
                SSLDetails.AuthType sslAuthType = datasourceConfiguration.getConnection().getSsl().getAuthType();
                try {
                    setSSLParam(dbBuilder, sslAuthType);
                } catch (AppsmithPluginException e) {
                    return Mono.error(e);
                }

                try {
                    setSSLContext(dbBuilder, datasourceConfiguration);
                } catch (AppsmithPluginException e) {
                    return Mono.error(e);
                }

                String dbName = auth.getDatabaseName();
                return Mono.just(dbBuilder.build().db(dbName));
            })
                    .flatMap(obj -> obj)
                    .subscribeOn(scheduler);
        }

        private Builder getBasicBuilder(DBAuth auth) {
            String username = auth.getUsername();
            String password = auth.getPassword();
            Builder dbBuilder = new Builder()
                    .user(username)
                    .password(password)
                    .useProtocol(Protocol.HTTP_VPACK);

            return dbBuilder;
        }

        private boolean isNonEmptyEndpoint(Endpoint endpoint) {
            if (endpoint != null && StringUtils.isNotNullOrEmpty(endpoint.getHost())) {
                return true;
            }

            return false;
        }

        private boolean isAuthenticationMissing(DBAuth auth) {
            if (auth == null
                    || StringUtils.isNullOrEmpty(auth.getUsername())
                    || StringUtils.isNullOrEmpty(auth.getPassword())
                    || StringUtils.isNullOrEmpty(auth.getDatabaseName())) {
                return true;
            }

            return false;
        }

        @Override
        public void datasourceDestroy(ArangoDatabase db) {
            db.arango().shutdown();
        }

        @Override
        public Set<String> validateDatasource(DatasourceConfiguration datasourceConfiguration) {
            Set<String> invalids = new HashSet<>();

            if (CollectionUtils.isEmpty(datasourceConfiguration.getEndpoints())) {
                invalids.add("No endpoint provided. Please provide a host:port where ArangoDB is reachable.");
            } else {
                Endpoint endpoint = datasourceConfiguration.getEndpoints().get(0);
                if (StringUtils.isNullOrEmpty(endpoint.getHost())) {
                    invalids.add("Missing host for endpoint");
                }
            }

            return invalids;
        }

        @Override
        public Mono<DatasourceTestResult> testDatasource(DatasourceConfiguration datasourceConfiguration) {
            return datasourceCreate(datasourceConfiguration)
                    .map(db -> {
                        db.getVersion();

                        if (db != null) {
                            db.arango().shutdown();
                        }

                        return new DatasourceTestResult();
                    })
                    .onErrorResume(error -> {
                        log.error("Error when testing ArangoDB datasource.", error);
                        return Mono.just(new DatasourceTestResult(error.getMessage()));
                    })
                    .subscribeOn(scheduler);
        }

        @Override
        public Mono<DatasourceStructure> getStructure(ArangoDatabase db, DatasourceConfiguration datasourceConfiguration) {
            final DatasourceStructure structure = new DatasourceStructure();
            List<DatasourceStructure.Table> tables = new ArrayList<>();
            structure.setTables(tables);

            CollectionsReadOptions options = new CollectionsReadOptions();
            options.excludeSystem(true);
            Collection<CollectionEntity> collections;
            try {
                collections = db.getCollections(options);
            } catch (ArangoDBException e) {
                return Mono.error(
                        new AppsmithPluginException(
                                AppsmithPluginError.PLUGIN_GET_STRUCTURE_ERROR,
                                "Appsmith server has failed to fetch list of collections from database. Please check " +
                                        "if the database credentials are valid and/or you have the required " +
                                        "permissions."
                        )
                );
            }

            return Flux.fromIterable(collections)
                    .filter(collectionEntity -> !collectionEntity.getIsSystem())
                    .flatMap(collectionEntity -> {
                        final ArrayList<DatasourceStructure.Column> columns = new ArrayList<>();
                        final ArrayList<DatasourceStructure.Template> templates = new ArrayList<>();
                        final String collectionName = collectionEntity.getName();
                        tables.add(
                                new DatasourceStructure.Table(
                                        DatasourceStructure.TableType.COLLECTION,
                                        null,
                                        collectionName,
                                        columns,
                                        new ArrayList<>(),
                                        templates
                                )
                        );

                        ArangoCursor<Map> cursor = db.query(getOneDocumentQuery(collectionName), null, null, Map.class);
                        Map document = new HashMap();
                        List<Map> docList = cursor.asListRemaining();
                        if (!CollectionUtils.isEmpty(docList)) {
                            document = docList.get(0);
                        }

                        return Mono.zip(
                                Mono.just(columns),
                                Mono.just(templates),
                                Mono.just(collectionName),
                                Mono.just(document)
                        );
                    })
                    .flatMap(tuple -> {
                        final ArrayList<DatasourceStructure.Column> columns = tuple.getT1();
                        final ArrayList<DatasourceStructure.Template> templates = tuple.getT2();
                        String collectionName = tuple.getT3();
                        Map document = tuple.getT4();

                        generateTemplatesAndStructureForACollection(collectionName, document, columns,
                                templates);

                        return Mono.just(structure);
                    })
                    .collectList()
                    .thenReturn(structure)
                    .subscribeOn(scheduler);
        }
    }
}
