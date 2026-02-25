from pydantic_settings import BaseSettings


class KafkaSettings(BaseSettings):
    bootstrap_servers: str = "localhost:9092"
    topic: str = "risk.transactions"

    model_config = {"env_prefix": "KAFKA_"}


class PostgresSettings(BaseSettings):
    host: str = "localhost"
    port: int = 5432
    user: str = "fraud_user"
    password: str = "fraud_pass"
    db: str = "fraud_db"

    model_config = {"env_prefix": "POSTGRES_"}

    @property
    def url(self) -> str:
        return f"postgresql://{self.user}:{self.password}@{self.host}:{self.port}/{self.db}"


class RedisSettings(BaseSettings):
    host: str = "localhost"
    port: int = 6379

    model_config = {"env_prefix": "REDIS_"}


class ElasticsearchSettings(BaseSettings):
    host: str = "localhost"
    port: int = 9200

    model_config = {"env_prefix": "ELASTICSEARCH_"}

    @property
    def url(self) -> str:
        return f"http://{self.host}:{self.port}"


class SparkSettings(BaseSettings):
    checkpoint_dir: str = "./checkpoint"
    log_level: str = "WARN"

    model_config = {"env_prefix": "SPARK_"}


class Settings(BaseSettings):
    kafka: KafkaSettings = KafkaSettings()
    postgres: PostgresSettings = PostgresSettings()
    redis: RedisSettings = RedisSettings()
    elasticsearch: ElasticsearchSettings = ElasticsearchSettings()
    spark: SparkSettings = SparkSettings()


def get_settings() -> Settings:
    return Settings()
