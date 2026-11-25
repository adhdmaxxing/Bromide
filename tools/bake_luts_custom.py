import sys, numpy as np, time
sys.path.insert(0,'/home/claude/agx-emulsion/src')
import colour
from spektrafilm.runtime.params_builder import init_params, digest_params
from spektrafilm.runtime.process import Simulator
from spektrafilm_lut_creator.bundles import BundleSpec

def bake(film, print_, n=65, scan_film=False):
    spec = BundleSpec(film_profile=film, print_profiles=(print_,), input_color_space="Sony S-Log3 (S-Gamut3.Cine)", output_color_space="sRGB")
    g = np.linspace(0, 1, n)
    b, gg, r = np.meshgrid(g, g, g, indexing='ij')         # cube order: r fastest
    enc = np.stack([r, gg, b], -1).reshape(n*n, n, 3)
    lin = colour.models.log_decoding_SLog3(enc)
    p = init_params(film, print_)
    p.debug.lut_mode = True
    p.io.input_color_space = 'S-Gamut3.Cine'
    p.io.output_color_space = 'sRGB'
    p.io.input_cctf_decoding = False
    p.io.output_cctf_encoding = True
    p.io.input_gamut_compress = spec.input_gamut_compress
    p.io.output_gamut_compress = spec.output_gamut_compress
    p.io.scan_film = scan_film
    p.camera.auto_exposure = False
    p = digest_params(p)
    out = Simulator(p).process(lin)
    return np.clip(out.reshape(n, n, n, 3), 0, 1).astype(np.float32)

if __name__ == '__main__':
    sys.path.insert(0,'/home/claude/bromide'); import bromide as fb
    t=time.time(); mine = bake('kodak_portra_400','kodak_portra_endura'); print('bake s', round(time.time()-t,1))
    flat, n = fb.get_lut('portra400')
    off = flat.reshape(n,n,n,3)
    d = np.abs(mine-off)
    print('vs official cube: mean %.4f p99 %.4f max %.4f'%(d.mean(), np.percentile(d,99), d.max()))
