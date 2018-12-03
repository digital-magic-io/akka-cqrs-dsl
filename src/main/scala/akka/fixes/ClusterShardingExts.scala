package akka.fixes

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardCoordinator, ShardRegion}

// Work-around from and for https://github.com/akka/akka/issues/24053
object ClusterShardingExts {
  object implicits {
    implicit final class ImplicitClusterShardingExts(clusterSharding: ClusterSharding) extends ClusterShardingExts(clusterSharding)
  }
}

sealed abstract class ClusterShardingExts(clusterSharding: ClusterSharding) {
  def startProperly
  (typeName: String,
   entityIdToEntityProps: ShardRegion.EntityId => Props,
   settings: ClusterShardingSettings,
   extractEntityId: ShardRegion.ExtractEntityId,
   extractShardId: ShardRegion.ExtractShardId,
   handOffStopMessage: Any
  ): ActorRef =
    clusterSharding.internalStart(
      typeName,
      entityIdToEntityProps,
      settings,
      extractEntityId,
      extractShardId,
      clusterSharding.defaultShardAllocationStrategy(settings),
      handOffStopMessage)
  def startProperly
    (typeName: String,
     entityIdToEntityProps: ShardRegion.EntityId => Props,
     settings: ClusterShardingSettings,
     extractEntityId: ShardRegion.ExtractEntityId,
     extractShardId: ShardRegion.ExtractShardId,
     allocationStrategy: ShardCoordinator.ShardAllocationStrategy,
     handOffStopMessage: Any
    ): ActorRef =
      clusterSharding.internalStart(
        typeName,
        entityIdToEntityProps,
        settings,
        extractEntityId,
        extractShardId,
        allocationStrategy,
        handOffStopMessage)
}