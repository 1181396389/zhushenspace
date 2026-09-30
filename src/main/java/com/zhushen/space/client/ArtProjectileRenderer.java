package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.*;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.entity.ModEntities;
import com.zhushen.space.entity.art.ArtProjectile;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Quaternionf;

/** Textured volumes/sweep surfaces, not generic additive lines, rings or particle clouds. */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ArtProjectileRenderer extends EntityRenderer<ArtProjectile> {
    private static ResourceLocation texture(String s) { return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "textures/entity/art/"+s+".png"); }
    private static final ResourceLocation FIRE = texture("fire"), WAVE = texture("wave"), SLASH = texture("slash"), BAGUA = texture("bagua");
    public ArtProjectileRenderer(EntityRendererProvider.Context c) { super(c); shadowRadius = 0; }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.ART_PROJECTILE.get(), ArtProjectileRenderer::new);
    }
    @Override public ResourceLocation getTextureLocation(ArtProjectile e) {
        return switch (e.kind()) { case ArtProjectile.FIREBALL, ArtProjectile.BURST -> FIRE;
            case ArtProjectile.WIND, ArtProjectile.SPIRIT -> SLASH; case ArtProjectile.SEAL -> BAGUA; default -> WAVE; };
    }
    @Override public boolean shouldRender(ArtProjectile e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        // The visual body can be much larger than the small logical entity box.
        return e.shouldRender(x,y,z) && frustum.isVisible(e.getBoundingBox().inflate(e.kind() == ArtProjectile.BURST ? 10 : 4));
    }
    @Override public void render(ArtProjectile e, float yaw, float partial, PoseStack stack, MultiBufferSource buffers, int light) {
        stack.pushPose();
        stack.mulPose(new Quaternionf().rotationY((float)Math.toRadians(-e.getYRot())));
        stack.mulPose(new Quaternionf().rotationX((float)Math.toRadians(e.getXRot())));
        // Newly spawned effects overlap the caster for the first flight tick; avoid a full-screen flash.
        if (e.tickCount < 1 && e.kind() != ArtProjectile.SEAL && e.kind() != ArtProjectile.BURST) { stack.popPose(); return; }
        float time = e.tickCount + partial;
        int frame = ((int)(time * 0.7f)) % 8;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(getTextureLocation(e)));
        switch (e.kind()) {
            case ArtProjectile.WIND, ArtProjectile.SPIRIT -> {
                stack.mulPose(new Quaternionf().rotationZ(-0.28f));
                // Actual thin curved sweep volume: front/back textured faces, soft silhouette.
                float alpha = Math.min(1f, time / 2f);
                plane(stack.last(),vc,2.0f,1.35f,0.04f,e.kind()==ArtProjectile.SPIRIT?0xFFD0ECFF:0xFFFFFFFF,alpha);
                stack.mulPose(new Quaternionf().rotationY((float)Math.PI));
                plane(stack.last(),vc,2.0f,1.35f,0.04f,e.kind()==ArtProjectile.SPIRIT?0xFFD0ECFF:0xFFFFFFFF,alpha);
            }
            case ArtProjectile.SEAL -> {
                stack.mulPose(new Quaternionf().rotationZ(time*.008f));
                float grow = Math.min(1, time/5);
                plane(stack.last(),vc,e.size()*grow,e.size()*grow,0,e.color(),1);
            }
            case ArtProjectile.WAVE -> sphere(stack.last(),vc,e.size(),e.size()*.7f,2.4f*e.size(),frame,0xFFFFFFFF,1,time,false);
            case ArtProjectile.LASER -> sphere(stack.last(),vc,.14f,.14f,2.4f,frame,e.color(),1,time,false);
            case ArtProjectile.FIREBALL -> sphere(stack.last(),vc,e.size()*e.flightScale(),e.size()*e.flightScale(),e.size()*e.flightScale()*1.15f,frame,0xFFFFFFFF,1,time,true);
            case ArtProjectile.BURST -> {
                float progress=Math.min(1,time/14f);
                float r=1.3f+8.7f*(1-(float)Math.pow(1-progress,3));
                sphere(stack.last(),vc,r,r,r,frame,0xFFFFFFFF,(1-progress)*.85f,time,true);
            }
        }
        stack.popPose();
    }
    private static void plane(PoseStack.Pose p,VertexConsumer vc,float x,float y,float z,int color,float alpha) {
        vertex(p,vc,-x,-y,z,0,1,color,alpha,0,0,1);vertex(p,vc,x,-y,z,1,1,color,alpha,0,0,1);
        vertex(p,vc,x,y,z,1,0,color,alpha,0,0,1);vertex(p,vc,-x,y,z,0,0,color,alpha,0,0,1);
    }
    private static void sphere(PoseStack.Pose p,VertexConsumer vc,float rx,float ry,float rz,int frame,int color,float alpha,float time,boolean flame) {
        int rows=18,cols=32;
        for(int y=0;y<rows;y++) for(int x=0;x<cols;x++) {
            point(p,vc,x,y,cols,rows,rx,ry,rz,frame,color,alpha,time,flame);
            point(p,vc,x+1,y,cols,rows,rx,ry,rz,frame,color,alpha,time,flame);
            point(p,vc,x+1,y+1,cols,rows,rx,ry,rz,frame,color,alpha,time,flame);
            point(p,vc,x,y+1,cols,rows,rx,ry,rz,frame,color,alpha,time,flame);
        }
    }
    private static void point(PoseStack.Pose p,VertexConsumer vc,int x,int y,int cols,int rows,float rx,float ry,float rz,int frame,int color,float alpha,float time,boolean flame) {
        float u=x/(float)cols,v=y/(float)rows;
        double a=u*Math.PI*2,b=v*Math.PI;
        float nx=(float)(Math.cos(a)*Math.sin(b)),ny=(float)Math.cos(b),nz=(float)(Math.sin(a)*Math.sin(b));
        float fold=flame?1+.055f*(float)Math.sin(a*5+b*6-time*.23)* (float)Math.sin(b):1;
        // Small surface undulation and UV flame tongues instead of disconnected geometric spikes.
        vertex(p,vc,nx*rx*fold,ny*ry*fold,nz*rz*fold,u,(frame+v)/8f,color,alpha,nx,ny,nz);
    }
    private static void vertex(PoseStack.Pose p,VertexConsumer vc,float x,float y,float z,float u,float v,int color,float alpha,float nx,float ny,float nz) {
        vc.addVertex(p.pose(),x,y,z).setColor((color>>16)&255,(color>>8)&255,color&255,(int)(255*Math.max(0,Math.min(1,alpha))))
                .setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p,nx,ny,nz);
    }
}
